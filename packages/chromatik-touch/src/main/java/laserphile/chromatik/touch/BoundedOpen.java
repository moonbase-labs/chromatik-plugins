package laserphile.chromatik.touch;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bytedeco.javacv.FrameGrabber;

import heronarts.lx.LX;

/**
 * Starts a webcam grabber with a deadline, so a device that never delivers a frame cannot wedge
 * the decode thread, and never more than one such open at a time per process.
 *
 * <p>FFmpeg's avfoundation input spins inside {@code avformat_open_input} until the camera hands it
 * a first frame, with no timeout option, no interrupt-callback check and no response to a thread
 * interrupt. A camera the OS has not granted to Chromatik, or one another app holds, therefore
 * never returns. Observed on 2026-10-25: the decode thread sat in that spin for fifteen minutes at
 * 100% CPU.
 *
 * <p>Worse than the spin was what it held. {@link org.bytedeco.javacv.FFmpegFrameGrabber#start()}
 * wraps the whole native open in {@code synchronized (avcodec.class)}, a lock shared by every
 * grabber in the process, so one stuck webcam open blocked every file open in the Video pattern
 * for as long as Chromatik ran, and each reopen there parked another thread on the same monitor.
 * The start here goes through {@code startUnsafe()} instead. That lock is a holdover from FFmpeg
 * builds before 4.0 that needed a lock manager around codec registration and {@code avcodec_open2};
 * the bundled FFmpeg 7.1 registers nothing at runtime and every codec is init-thread-safe, so two
 * opens in flight at once are fine and a stuck one holds nothing anyone else needs.
 *
 * <p>The start runs on its own daemon thread and the caller waits on it for at most
 * {@link #DEADLINE_MS}. Past the deadline the caller gives up, says so in the log naming Camera
 * permission as the likely cause, and fails its open; the opener thread is left to spin, and if
 * the device ever does answer, the opener releases it. Only one opener may be outstanding per
 * process: a second open while one is still inside the device waits for it if it is young (the
 * user switched cameras during a healthy open) and is refused if it is past the deadline, so a
 * pattern toggled off and on against a dead camera does not stack up spinning threads.
 */
final class BoundedOpen {

  /**
   * Long enough for a genuine open, which is tens of milliseconds once FFmpeg's natives are
   * extracted and around six seconds the first time in a session while they are.
   */
  static final long DEADLINE_MS = 15_000;

  private static final Object LOCK = new Object();

  /** The one open still inside a device, or null. Guarded by {@link #LOCK}. */
  private static Outstanding outstanding;

  private record Outstanding(Thread thread, String description, long startedNanos) {
    long ageMs() {
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.startedNanos);
    }
  }

  /** The native start to run; {@code FFmpegFrameGrabber::startUnsafe} for an FFmpeg grabber. */
  interface StartAction {
    void start() throws Exception;
  }

  private BoundedOpen() {
  }

  /**
   * Run {@code action} on an opener thread and wait for it to finish, for at most the deadline.
   *
   * Returns normally once the grabber is started and owned by the caller. Throws when the start
   * failed, when the deadline passed, or when another open is already stuck. On a throw the caller
   * must not touch the grabber again: it has either been released here, or it belongs to the
   * opener thread, which releases it if the start ever completes. Calling {@code release()} on a
   * grabber whose start is still running would wait on the grabber's own monitor, which that start
   * holds, while itself holding JavaCV's process-wide lock: exactly the starvation this class is
   * here to prevent.
   *
   * @param grabber the grabber being started, released on any failure by whichever side owns it
   * @param action the start call itself
   * @param description what is being opened, for the log
   * @param logTag the calling pattern's log prefix
   */
  static void start(FrameGrabber grabber, StartAction action, String description, String logTag)
    throws Exception {

    // Whichever side gets here first owns the result: the opener if it finishes before the caller
    // gives up, the caller otherwise. The loser of that race is the one that releases the device.
    final AtomicBoolean settled = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final CountDownLatch done = new CountDownLatch(1);

    final Thread opener = new Thread(() -> {
      try {
        action.start();
      } catch (Throwable thrown) {
        failure.set(thrown);
      }

      if (!settled.compareAndSet(false, true)) {
        // The caller gave up on us. The device is ours to put back, started or not.
        releaseQuietly(grabber);
        LX.log(String.format(
          "%s the abandoned webcam open (%s) returned after %d s%s. The device has been released; "
            + "toggle the pattern off and on to retry.",
          logTag,
          description,
          ageOfOutstanding() / 1000,
          failure.get() == null ? "" : " with " + failure.get()));
      }

      releaseSlot();
      done.countDown();
    }, String.format("laserphile-webcam-open %s", description));
    opener.setDaemon(true);

    try {
      claimSlot(opener, description, logTag);
    } catch (Exception refused) {
      releaseQuietly(grabber); // never started, but the caller is done with it
      throw refused;
    }
    opener.start();

    final boolean finished;
    try {
      finished = done.await(DEADLINE_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      // The pipeline is stopping us mid-open. If the opener already settled, its result is ours
      // to dispose of; otherwise it will see that we left and release the device itself.
      if (!settled.compareAndSet(false, true)) {
        awaitUninterruptibly(done);
        releaseQuietly(grabber);
      }
      throw interrupted;
    }

    if (!finished && settled.compareAndSet(false, true)) {
      LX.log(String.format(
        "%s webcam open (%s) has not returned after %d s and has been abandoned. FFmpeg waits "
          + "forever for a first frame the camera never delivers: most likely Chromatik lacks "
          + "Camera permission (System Settings > Privacy & Security > Camera; restart Chromatik "
          + "after granting it), or another app holds the camera. The opener thread '%s' keeps "
          + "spinning until the device answers; no other webcam open will be attempted until it "
          + "does.",
        logTag,
        description,
        DEADLINE_MS / 1000,
        opener.getName()));

      throw new IllegalStateException(String.format(
        "webcam open (%s) did not return within %d s; Camera permission is the usual cause",
        description,
        DEADLINE_MS / 1000));
    }

    // Either the opener finished in time, or it settled a moment after the deadline and the
    // result is ours after all. Its countDown follows its settle, so this wait is momentary.
    awaitUninterruptibly(done);

    final Throwable thrown = failure.get();
    if (thrown == null) {
      return;
    }

    releaseQuietly(grabber);
    if (thrown instanceof Exception exception) {
      throw exception;
    }
    throw new IllegalStateException("webcam open failed: " + thrown, thrown);
  }

  /**
   * Take the single opener slot for {@code opener}.
   *
   * A young outstanding open is probably healthy and about to finish (the user switched cameras
   * while the first was still coming up), so it is waited on for what is left of its deadline. One
   * past the deadline is stuck, and this open is refused rather than becoming a second spinning
   * thread.
   */
  private static void claimSlot(Thread opener, String description, String logTag)
    throws Exception {

    while (true) {
      final Outstanding current;
      synchronized (LOCK) {
        if (outstanding == null || !outstanding.thread().isAlive()) {
          outstanding = new Outstanding(opener, description, System.nanoTime());
          return;
        }
        current = outstanding;
      }

      final long remainingMs = DEADLINE_MS - current.ageMs();

      if (remainingMs <= 0) {
        throw new IllegalStateException(String.format(
          "not opening webcam (%s): the earlier open (%s) has been stuck in FFmpeg for %d s on "
            + "thread '%s'. Check Camera permission for Chromatik, then restart Chromatik.",
          description,
          current.description(),
          current.ageMs() / 1000,
          current.thread().getName()));
      }

      LX.log(String.format(
        "%s waiting up to %d s for the previous webcam open (%s) before opening %s",
        logTag,
        Math.max(1, remainingMs / 1000),
        current.description(),
        description));

      current.thread().join(remainingMs);
    }
  }

  /** Give the slot up, if it is still ours. Called on the opener thread. */
  private static void releaseSlot() {
    synchronized (LOCK) {
      if (outstanding != null && outstanding.thread() == Thread.currentThread()) {
        outstanding = null;
      }
    }
  }

  /** How long the current thread's open has been running, for the opener's own log line. */
  private static long ageOfOutstanding() {
    synchronized (LOCK) {
      if (outstanding != null && outstanding.thread() == Thread.currentThread()) {
        return outstanding.ageMs();
      }
    }
    return 0;
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void releaseQuietly(FrameGrabber grabber) {
    try {
      grabber.stop();
      grabber.release();
    } catch (Throwable ignored) {
      // The caller has already moved on; there is nobody to tell.
    }
  }
}
