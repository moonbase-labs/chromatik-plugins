package laserphile.chromatik.video;

/**
 * One decoded video frame as packed ARGB pixels (0xAARRGGBB), row-major, top-left origin.
 * Each frame owns its own array, so frames can be handed between threads without aliasing.
 */
final class VideoFrame {

  final int[] argb;
  final int width;
  final int height;

  /** Presentation time within the media, straight from the decoder. Restarts at 0 on a loop. */
  final long mediaTimeMs;

  /**
   * Presentation time on the continuous timeline the playback clock runs on: unlike
   * {@link #mediaTimeMs} it keeps climbing across a loop boundary instead of restarting, so the
   * engine can compare it against the clock without special-casing the seam.
   *
   * Stamped by {@link FramePipeline} on the decode thread before the frame is published, and
   * never written again, so readers on the engine thread see a stable value.
   */
  long streamTimeMs;

  /**
   * Which seek this frame belongs to. The engine numbers its seeks and discards frames still in
   * flight from earlier ones, so scrubbing never briefly shows footage from the old position.
   */
  int seekGeneration;

  VideoFrame(int[] argb, int width, int height, long mediaTimeMs) {
    this.argb = argb;
    this.width = width;
    this.height = height;
    this.mediaTimeMs = mediaTimeMs;
  }
}
