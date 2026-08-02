package laserphile.chromatik.video;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import heronarts.lx.LX;

/**
 * Owns the background decode thread and the frame buffer between it and the engine thread.
 *
 * Decoded frames go into a small bounded ring in presentation order. The engine calls
 * {@link #frameFor} once per render and never blocks: it takes the newest buffered frame that
 * is due and leaves the rest. A full ring is the only brake on decoding, which gives back-
 * pressure for free: pausing or playing below 1x stalls the decode thread on the ring, and
 * playing above 1x drains the ring so decode runs flat out to keep up. If decode still cannot
 * keep up, the engine keeps showing the newest frame it has and the clock carries on, so media
 * time stays honest and frames are dropped instead.
 *
 * Control flows the other way through a mailbox that the decode thread reads between frames: a
 * volatile for looping and an atomic seek request. A seek carries a generation number, so a
 * rapid scrub coalesces down to its newest target and any frames still in flight from earlier
 * targets are discarded rather than shown.
 */
final class FramePipeline {

  /**
   * Eight frames is about a quarter second of lookahead at 30fps: enough to absorb decode
   * jitter, short enough that pause and seek still feel immediate.
   */
  private static final int RING_CAPACITY = 8;

  /** Longest the decode thread parks on a full or finished stream before re-reading the mailbox. */
  private static final long TWENTY_MILLISECONDS = 20;

  private static final long TWO_SECONDS_IN_MS = 2000;

  private final BlockingQueue<VideoFrame> ring = new ArrayBlockingQueue<>(RING_CAPACITY);
  private final AtomicReference<SeekRequest> seekRequest = new AtomicReference<>();

  private volatile boolean running = false;
  private volatile boolean looping = true;
  private volatile boolean endOfStream = false;
  private volatile long durationMs = FrameSource.DURATION_UNKNOWN;

  private Thread thread;

  // Engine-thread state, never touched by the decode thread.
  private VideoFrame current;
  private int seekGeneration = 0;

  /** A seek order. Only the newest matters, so a new one replaces the old rather than queueing. */
  private record SeekRequest(int generation, long mediaTimeMs) {}

  void start(String path) {
    stop();

    this.running = true;
    this.thread = new Thread(() -> decodeLoop(path), "laserphile-decode");
    this.thread.setDaemon(true);
    this.thread.start();
  }

  private void decodeLoop(String path) {
    final FileVideoSource source = new FileVideoSource(path);

    try {
      source.open();

      this.durationMs = source.durationMs();

      final long frameIntervalMs = Math.max(1, Math.round(1000.0 / source.frameRate()));

      // The generation this thread is decoding for; frames are stamped with it so the engine can
      // spot ones left over from a position the user has already scrubbed away from.
      int generation = 0;

      // Frames are stamped onto a continuous timeline: the offset re-anchors after an open, a
      // seek, or a loop, and otherwise just rides along on top of each frame's own media time.
      long streamOffsetMs = 0;
      long anchorStreamTimeMs = 0;
      long lastStreamTimeMs = 0;
      boolean anchorNextFrame = true;

      // Decoded but not yet accepted by a full ring, held over to the next iteration.
      VideoFrame pending = null;

      while (this.running) {
        final SeekRequest request = this.seekRequest.get();

        if (request != null && request.generation() != generation) {
          generation = request.generation();
          anchorStreamTimeMs = request.mediaTimeMs();
          anchorNextFrame = true;
          pending = null;
          this.endOfStream = false;

          this.ring.clear();
          source.seek(request.mediaTimeMs());

          continue;
        }

        if (pending == null) {
          final VideoFrame decoded = source.grab();

          if (decoded == null) {
            if (!this.looping) {
              this.endOfStream = true;
              Thread.sleep(TWENTY_MILLISECONDS);
              continue;
            }

            // Gapless loop: rewind here rather than round-tripping through the engine, and carry
            // the timeline straight on across the seam so the clock needs no special case.
            this.endOfStream = false;
            anchorStreamTimeMs = lastStreamTimeMs + frameIntervalMs;
            anchorNextFrame = true;

            source.seek(0);

            continue;
          }

          if (anchorNextFrame) {
            // A seek lands on a keyframe at or before the target, and some files do not start at
            // zero, so pin this frame to the time that was actually asked for and measure the
            // rest of the run from there.
            streamOffsetMs = anchorStreamTimeMs - decoded.mediaTimeMs;
            anchorNextFrame = false;
          }

          decoded.streamTimeMs = streamOffsetMs + decoded.mediaTimeMs;
          decoded.seekGeneration = generation;
          lastStreamTimeMs = decoded.streamTimeMs;

          pending = decoded;
        }

        if (this.ring.offer(pending, TWENTY_MILLISECONDS, TimeUnit.MILLISECONDS)) {
          pending = null;
        }
      }
    } catch (InterruptedException interrupted) {
      // Normal shutdown path (stop() interrupts the thread).
    } catch (Exception failure) {
      LX.log("[LaserphileVideo] decode error for " + path + ": " + failure);
    } finally {
      source.close();
      this.ring.clear();
    }
  }

  /**
   * The newest buffered frame due at {@code streamTimeMs}, or the one already on screen if
   * nothing newer is due. Non-blocking, and never steps backwards to an older frame. Returns
   * null only before the first frame of a file has been decoded.
   */
  VideoFrame frameFor(long streamTimeMs) {
    VideoFrame head;

    while ((head = this.ring.peek()) != null) {
      final boolean showingCurrentSeek =
        this.current != null && this.current.seekGeneration == this.seekGeneration;

      // Frames from a superseded seek are dropped outright; the first frame of a new seek is
      // taken whatever its timestamp, since the timeline has just jumped underneath it.
      if (head.seekGeneration == this.seekGeneration
        && showingCurrentSeek
        && head.streamTimeMs > streamTimeMs) {
        break;
      }

      final VideoFrame taken = this.ring.poll();
      if (taken == null) {
        break; // a concurrent seek flushed the ring between the peek and the poll
      }

      if (taken.seekGeneration == this.seekGeneration) {
        this.current = taken;
      }
    }

    return this.current;
  }

  /** Post a seek for the decode thread. Repeated calls coalesce to the newest target. */
  void requestSeek(long mediaTimeMs) {
    this.seekGeneration++;
    this.endOfStream = false;
    this.seekRequest.set(new SeekRequest(this.seekGeneration, Math.max(0, mediaTimeMs)));
  }

  void setLooping(boolean looping) {
    this.looping = looping;
  }

  long durationMs() {
    return this.durationMs;
  }

  /** True once the source has run out with looping off and the buffered frames have played out. */
  boolean isDrained() {
    return this.endOfStream && this.ring.isEmpty();
  }

  void stop() {
    this.running = false;

    if (this.thread != null) {
      this.thread.interrupt();

      try {
        this.thread.join(TWO_SECONDS_IN_MS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }

      this.thread = null;
    }

    this.ring.clear();
    this.seekRequest.set(null);
    this.current = null;
    this.seekGeneration = 0;
    this.endOfStream = false;
    this.durationMs = FrameSource.DURATION_UNKNOWN;
  }
}
