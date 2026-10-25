package laserphile.chromatik.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import heronarts.lx.LX;

/**
 * Owns the background decode thread and the frame buffer between it and the engine thread.
 *
 * Recorded and live sources are buffered differently, because they want opposite things.
 *
 * A recorded source has a timeline to honour. Decoded frames go into a small bounded ring in
 * presentation order, and the engine calls {@link #frameFor} once per render and never blocks: it
 * takes the newest buffered frame that is due and leaves the rest. A full ring is the only brake on
 * decoding, which gives back-pressure for free: pausing or playing below 1x stalls the decode
 * thread on the ring, and playing above 1x drains the ring so decode runs flat out to keep up. If
 * decode still cannot keep up, the engine keeps showing the newest frame it has and the clock
 * carries on, so media time stays honest and frames are dropped instead.
 *
 * A live source has no timeline and only one interesting frame, the one happening now. It gets a
 * single slot that the capture thread overwrites and the engine reads, so neither side ever waits
 * on the other. Buffering ahead would be the wrong goal here: a ring would just add its own depth
 * as latency between the desktop and the LEDs.
 *
 * Control flows the other way through a mailbox that the decode thread reads between frames: a
 * volatile for looping and an atomic seek request. A seek carries a generation number, so a
 * rapid scrub coalesces down to its newest target and any frames still in flight from earlier
 * targets are discarded rather than shown.
 *
 * A decode thread can get stuck somewhere no interrupt reaches: inside a capture device that never
 * delivers a first frame, or on the process-wide monitor JavaCV takes around every decoder open,
 * which a stuck open anywhere else in the process holds forever. Neither {@link #stop()} nor
 * {@link #start} may wait on that. A thread that does not exit is abandoned and remembered, the log
 * says so once, and no new thread is started on this pipeline while it lives: a second one would
 * only join it on the same lock, and each reopen would leave another behind. The start that was
 * asked for is kept instead and happens as soon as the old thread finally goes, or on the next
 * open after that. A watchdog also reports any open that has been running longer than a few
 * seconds, so a stuck open shows up in the log without a thread dump.
 */
public final class FramePipeline {

  /**
   * Eight frames is about a quarter second of lookahead at 30fps: enough to absorb decode
   * jitter, short enough that pause and seek still feel immediate.
   */
  private static final int RING_CAPACITY = 8;

  /** Longest the decode thread parks on a full or finished stream before re-reading the mailbox. */
  private static final long TWENTY_MILLISECONDS = 20;

  private static final long TWO_SECONDS_IN_MS = 2000;

  /**
   * How long an open may run before the watchdog mentions it. Long enough that a normal open
   * (tens of milliseconds) never trips it; the first open of a session, which also pays for
   * extracting FFmpeg's natives, can, and the line that follows when it returns says it was fine.
   */
  private static final long SLOW_OPEN_MS = 5000;

  /**
   * One thread for every pipeline in the process, used only to say that an open is taking too
   * long. A daemon, so it cannot keep Chromatik alive on exit.
   */
  private static final ScheduledExecutorService OPEN_WATCHDOG =
    Executors.newSingleThreadScheduledExecutor(task -> {
      final Thread thread = new Thread(task, "laserphile-open-watchdog");
      thread.setDaemon(true);
      return thread;
    });

  /**
   * How many grabs may fail in a row before the decode thread stops trying. A damaged frame in the
   * middle of an otherwise readable file should cost that frame and nothing else, so failures are
   * retried; a file that has genuinely stopped being readable would otherwise spin forever, so a
   * run this long is taken as the source being gone rather than one bad frame.
   */
  private static final int CONSECUTIVE_DECODE_FAILURES_ALLOWED = 10;

  private final BlockingQueue<VideoFrame> ring = new ArrayBlockingQueue<>(RING_CAPACITY);
  private final AtomicReference<SeekRequest> seekRequest = new AtomicReference<>();

  /** The live single slot: whatever the capture thread saw most recently. */
  private final AtomicReference<VideoFrame> latestLiveFrame = new AtomicReference<>();

  /**
   * Which start() each decode thread belongs to. A thread checks this against its own copy and
   * stands down once it no longer matches, which is what keeps a thread abandoned by {@link #stop()}
   * from touching state a later thread now owns. The {@code running} flag cannot do that job on its
   * own, because a restart sets it back to true and an abandoned thread would read it as permission
   * to carry on.
   */
  private final AtomicInteger startEpoch = new AtomicInteger();

  private volatile boolean running = false;
  private volatile boolean looping = true;
  private volatile boolean endOfStream = false;
  private volatile boolean publishedAnyFrame = false;
  private volatile long durationMs = FrameSource.DURATION_UNKNOWN;

  private Thread thread;

  /**
   * A decode thread that {@link #stop()} could not get to exit, kept so that {@link #start} can
   * tell whether it is still around. Null once it has exited or when nothing was ever abandoned.
   */
  private Thread abandonedThread;

  /** A start held back by {@link #abandonedThread}, run once that thread exits. */
  private FrameSource deferredSource;
  private int deferredLongestEdge;

  // Engine-thread state, never touched by the decode thread.
  private VideoFrame current;
  private int seekGeneration = 0;

  /** A seek order. Only the newest matters, so a new one replaces the old rather than queueing. */
  private record SeekRequest(int generation, long mediaTimeMs) {}

  public void start(FrameSource source, int longestEdge) {
    stop();

    if (this.abandonedThread != null) {
      LX.log(String.format(
        "[LaserphileVideo] not starting decode for %s yet: the previous decode thread '%s' has "
          + "still not exited. It starts as soon as that thread does; if the log never says so, "
          + "restart Chromatik.",
        source,
        this.abandonedThread.getName()));
      this.deferredSource = source;
      this.deferredLongestEdge = longestEdge;
      return;
    }

    launch(source, longestEdge);
  }

  private void launch(FrameSource source, int longestEdge) {
    final int epoch = this.startEpoch.incrementAndGet();

    this.running = true;

    // Named after the source because two patterns can be running at once, and a thread dump is the
    // only way to tell which of them is the one wedged in a capture that never opened.
    this.thread = new Thread(
      () -> openAndRun(source, longestEdge, epoch),
      String.format("laserphile-decode %s", source));
    this.thread.setDaemon(true);
    this.thread.start();
  }

  /**
   * Run a start that {@link #start} held back, if the thread that held it back has gone.
   *
   * Called from the engine-thread reads, which happen every render, so the deferred start lands
   * within a frame of the old thread exiting rather than waiting for the user to do something.
   */
  private void startDeferredIfUnblocked() {
    if (this.deferredSource == null || !forgetAbandonedThreadIfExited()) {
      return;
    }

    final FrameSource source = this.deferredSource;
    final int longestEdge = this.deferredLongestEdge;
    this.deferredSource = null;

    LX.log(String.format("[LaserphileVideo] starting the held-back decode for %s", source));
    launch(source, longestEdge);
  }

  /**
   * Drop the abandoned thread once it has exited, with a log line to close off the one that said
   * it was stuck. Returns true when no abandoned thread is in the way.
   */
  private boolean forgetAbandonedThreadIfExited() {
    if (this.abandonedThread == null) {
      return true;
    }
    if (this.abandonedThread.isAlive()) {
      return false;
    }

    LX.log(String.format(
      "[LaserphileVideo] the abandoned decode thread '%s' has now exited",
      this.abandonedThread.getName()));
    this.abandonedThread = null;
    return true;
  }

  /**
   * Open the source, then hand off to whichever loop suits it.
   *
   * open() can block for a long time and, for screen capture without permission, forever. It runs
   * here rather than in start() so that only this daemon thread ever waits on it.
   */
  private void openAndRun(FrameSource source, int longestEdge, int epoch) {
    try {
      openWatched(source, longestEdge);

      this.durationMs = source.durationMs();

      if (source.isLive()) {
        captureLoop(source, epoch);
      } else {
        decodeLoop(source, epoch);
      }
    } catch (InterruptedException interrupted) {
      // Normal shutdown path (stop() interrupts the thread).
    } catch (Exception failure) {
      LX.log(String.format("[LaserphileVideo] decode error for %s: %s", source, failure));
    } catch (Throwable failure) {
      LX.log(String.format("[LaserphileVideo] fatal source error for %s: %s", source, failure));
    } finally {
      source.close();

      // A thread that outlived its stop() must not clear a buffer the current one is filling.
      if (isCurrent(epoch)) {
        this.ring.clear();
        this.latestLiveFrame.set(null);
      }
    }
  }

  /**
   * Open the source under a watchdog that names it in the log if it takes longer than
   * {@link #SLOW_OPEN_MS}, and again when it comes back, so a stuck open and a merely slow one can
   * be told apart from the log alone.
   */
  private void openWatched(FrameSource source, int longestEdge) throws Exception {
    final String threadName = Thread.currentThread().getName();
    final long startedNanos = System.nanoTime();

    final ScheduledFuture<?> alarm = OPEN_WATCHDOG.schedule(() -> {
      try {
        LX.log(String.format(
          "[LaserphileVideo] open of %s has not returned after %d s (thread '%s'). The first open "
            + "of a session can take this long while FFmpeg's natives are extracted, and a line "
            + "follows when it returns. If none does, the open is stuck inside the device (a "
            + "camera or screen capture Chromatik has no permission for) or waiting on JavaCV's "
            + "process-wide decoder lock, which a stuck open anywhere in Chromatik holds for as "
            + "long as it is stuck. Restart Chromatik to clear it.",
          source,
          SLOW_OPEN_MS / 1000,
          threadName));
      } catch (Throwable ignored) {
        // Logging must never take the watchdog thread down.
      }
    }, SLOW_OPEN_MS, TimeUnit.MILLISECONDS);

    boolean opened = false;
    try {
      source.open(longestEdge);
      opened = true;
    } finally {
      alarm.cancel(false);

      final long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
      if (tookMs >= SLOW_OPEN_MS) {
        LX.log(String.format(
          "[LaserphileVideo] open of %s %s after %.1f s",
          source,
          opened ? "returned" : "failed",
          tookMs / 1000.0));
      }
    }
  }

  /**
   * Keep the single slot pointed at the newest captured frame. No timeline, no back-pressure: an
   * engine that renders slower than the capture rate simply misses the frames in between, which is
   * the correct answer for live footage.
   */
  private void captureLoop(FrameSource source, int epoch) throws Exception {
    int failuresSinceGoodFrame = 0;

    while (isCurrent(epoch)) {
      final VideoFrame captured;

      // A desktop can go to sleep, change resolution, or lose the display underneath the capture,
      // and each of those surfaces as a failed grab rather than as the device closing. Worth
      // riding out, since whatever the user did is usually over in a moment.
      try {
        captured = source.grab();
      } catch (InterruptedException interrupted) {
        throw interrupted; // shutdown, not a capture fault
      } catch (Exception failure) {
        failuresSinceGoodFrame = noteDecodeFailure(source, failure, failuresSinceGoodFrame);

        Thread.sleep(TWENTY_MILLISECONDS);
        continue;
      }

      failuresSinceGoodFrame = 0;

      if (captured == null) {
        Thread.sleep(TWENTY_MILLISECONDS);
        continue;
      }

      // The engine ignores both stamps for a live source; they are set so that nothing downstream
      // has to read a half-initialised frame.
      captured.streamTimeMs = captured.mediaTimeMs;
      captured.seekGeneration = 0;

      // Re-checked because grab() blocks: this thread may have been abandoned while it waited, and
      // the slot it is about to write may belong to a source that started in the meantime.
      if (!isCurrent(epoch)) {
        return;
      }

      this.latestLiveFrame.set(captured);
      this.publishedAnyFrame = true;
    }
  }

  private void decodeLoop(FrameSource source, int epoch) throws Exception {
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

    // Grabs that have failed since the last good frame. Reset by any frame that arrives.
    int failuresSinceGoodFrame = 0;

    while (isCurrent(epoch)) {
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
        final VideoFrame decoded;

        try {
          decoded = source.grab();
        } catch (InterruptedException interrupted) {
          throw interrupted; // shutdown, not a decode fault
        } catch (Exception failure) {
          failuresSinceGoodFrame =
            noteDecodeFailure(source, failure, failuresSinceGoodFrame);

          Thread.sleep(TWENTY_MILLISECONDS);
          continue;
        }

        failuresSinceGoodFrame = 0;

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
        this.publishedAnyFrame = true;
      }
    }
  }

  /**
   * Count a failed grab and decide whether to carry on, returning the new run length.
   *
   * Only the first failure of a run is logged, because a file that has stopped being readable
   * fails every time it is asked and would otherwise fill the log before it gives up. Passing the
   * failure back out once the run is long enough hands it to the caller's own reporting.
   */
  private static int noteDecodeFailure(FrameSource source, Exception failure, int alreadyFailed)
    throws Exception {

    final int failures = alreadyFailed + 1;

    if (failures == 1) {
      LX.log(String.format(
        "[LaserphileVideo] decode error for %s, skipping the frame: %s", source, failure));
    }

    if (failures >= CONSECUTIVE_DECODE_FAILURES_ALLOWED) {
      throw failure;
    }

    return failures;
  }

  /** Whether a decode thread still owns this pipeline, or has been superseded by a later start(). */
  private boolean isCurrent(int epoch) {
    return this.running && this.startEpoch.get() == epoch;
  }

  /**
   * The newest buffered frame due at {@code streamTimeMs}, or the one already on screen if
   * nothing newer is due. Non-blocking, and never steps backwards to an older frame. Returns
   * null only before the first frame of a file has been decoded.
   *
   * Pairs with a recorded source. A live source fills the other buffer, so calling this on one
   * returns null for as long as it runs rather than failing outright.
   */
  public VideoFrame frameFor(long streamTimeMs) {
    startDeferredIfUnblocked();

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

  /**
   * Whatever was captured most recently, or null before the first frame arrives.
   *
   * Pairs with a live source. There is no time argument because a live source has no timeline: the
   * newest frame is the only one it holds and the only one worth showing.
   */
  public VideoFrame latestFrame() {
    startDeferredIfUnblocked();

    return this.latestLiveFrame.get();
  }

  /** Post a seek for the decode thread. Repeated calls coalesce to the newest target. */
  public void requestSeek(long mediaTimeMs) {
    this.seekGeneration++;
    this.endOfStream = false;
    this.seekRequest.set(new SeekRequest(this.seekGeneration, Math.max(0, mediaTimeMs)));
  }

  public void setLooping(boolean looping) {
    this.looping = looping;
  }

  public long durationMs() {
    return this.durationMs;
  }

  /** True once the source has run out with looping off and the buffered frames have played out. */
  public boolean isDrained() {
    return this.endOfStream && this.ring.isEmpty();
  }

  /**
   * True once any frame at all has reached the buffer since the current source was started. A
   * source that stays false is one that opened but is producing nothing, which is how a screen
   * capture without permission presents itself.
   */
  public boolean hasPublishedFrame() {
    return this.publishedAnyFrame;
  }

  /**
   * Stop the decode thread and clear the buffers.
   *
   * The join is bounded because the thread can be somewhere that does not answer an interrupt: a
   * screen capture waiting on a first frame the OS will never send blocks inside FFmpeg with no way
   * to break in, and a file open queued behind it on JavaCV's process-wide lock is just as deaf.
   * Rather than hold the engine up, the thread is left to its own devices; it is a daemon, so it
   * cannot keep the app alive, and the epoch it captured at start stops it from touching anything
   * once it does come back. It is remembered, though, so that {@link #start} does not pile another
   * thread onto the same obstacle, and the log says once that it is stuck.
   */
  public void stop() {
    this.running = false;

    if (this.deferredSource != null) {
      // Never opened, so there is nothing to release, but a source may own other things.
      this.deferredSource.close();
      this.deferredSource = null;
    }

    forgetAbandonedThreadIfExited();

    if (this.thread != null) {
      this.thread.interrupt();

      try {
        this.thread.join(TWO_SECONDS_IN_MS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }

      if (this.thread.isAlive()) {
        LX.log(String.format(
          "[LaserphileVideo] decode thread '%s' has not exited %d s after being stopped. It is "
            + "blocked in native code or on a lock that an interrupt cannot break, most likely a "
            + "capture device with no permission or JavaCV's process-wide decoder lock held by one. "
            + "It has been abandoned; no new decode starts on this pipeline until it exits, and "
            + "only a restart of Chromatik is certain to clear it.",
          this.thread.getName(),
          TWO_SECONDS_IN_MS / 1000));

        // At most one thread is ever abandoned, because start() refuses to run while one lives.
        this.abandonedThread = this.thread;
      }

      this.thread = null;
    }

    this.ring.clear();
    this.latestLiveFrame.set(null);
    this.seekRequest.set(null);
    this.current = null;
    this.seekGeneration = 0;
    this.endOfStream = false;
    this.publishedAnyFrame = false;
    this.durationMs = FrameSource.DURATION_UNKNOWN;
  }
}
