package laserphile.chromatik.video;

import java.util.concurrent.atomic.AtomicReference;

import heronarts.lx.LX;

/**
 * Owns the background decode thread and publishes the most recently decoded frame for the
 * engine thread to read. The engine never blocks: it just reads {@link #latest()}.
 *
 * M1 uses a single latest-frame holder and paces decode to the source frame rate with a
 * sleep. The bounded ring buffer + proper playback clock (seek, speed, drop policy) arrive
 * in M3; this crude pacing is a placeholder so playback looks roughly real-time.
 */
final class FramePipeline {

  private final AtomicReference<VideoFrame> latest = new AtomicReference<>();

  private volatile boolean running = false;
  private Thread thread;

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

      final long frameIntervalMs = Math.max(1, Math.round(1000.0 / source.frameRate()));

      while (this.running) {
        final VideoFrame frame = source.grab();

        if (frame == null) {
          // End of stream: loop back to the start.
          source.seekToStart();
          continue;
        }

        this.latest.set(frame);

        Thread.sleep(frameIntervalMs);
      }
    } catch (InterruptedException interrupted) {
      // Normal shutdown path (stop() interrupts the thread).
    } catch (Exception failure) {
      LX.log("[LaserphileVideo] decode error for " + path + ": " + failure);
    } finally {
      source.close();
      this.latest.set(null);
    }
  }

  VideoFrame latest() {
    return this.latest.get();
  }

  void stop() {
    this.running = false;

    if (this.thread != null) {
      this.thread.interrupt();

      try {
        this.thread.join(2000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }

      this.thread = null;
    }

    this.latest.set(null);
  }
}
