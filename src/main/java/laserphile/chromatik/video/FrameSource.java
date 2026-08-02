package laserphile.chromatik.video;

/**
 * A source of decoded video frames. The seam that lets file playback and (later) live screen
 * capture share one decode/projection pipeline. All methods run on the decode thread, never
 * the LX engine thread, so blocking here is fine.
 */
interface FrameSource extends AutoCloseable {

  /** Reported by {@link #durationMs()} when the source has no timeline (a live capture). */
  long DURATION_UNKNOWN = -1;

  /** Open the underlying decoder / capture device. */
  void open() throws Exception;

  /** Frames per second to pace playback at. Falls back to a sane default if unknown. */
  double frameRate();

  /** Total media length, or {@link #DURATION_UNKNOWN} if the source has no timeline. */
  long durationMs();

  /** The next decoded frame, or null at end of stream. */
  VideoFrame grab() throws Exception;

  /**
   * Jump to a media time. Seeks snap to the nearest keyframe at or before the target, so the
   * next frame grabbed can be a little earlier than asked for. No-op for non-seekable sources.
   */
  void seek(long mediaTimeMs) throws Exception;

  @Override
  void close();
}
