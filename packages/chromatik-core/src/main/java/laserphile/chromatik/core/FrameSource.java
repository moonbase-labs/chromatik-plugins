package laserphile.chromatik.core;

/**
 * A source of decoded video frames. The seam that lets file playback and (later) live screen
 * capture share one decode/projection pipeline. All methods run on the decode thread, never
 * the LX engine thread, so blocking here is fine.
 */
public interface FrameSource extends AutoCloseable {

  /** Reported by {@link #durationMs()} when the source has no timeline (a live capture). */
  long DURATION_UNKNOWN = -1;

  /**
   * Open the underlying decoder / capture device, producing frames no larger than
   * {@code longestEdge} on their longest side.
   *
   * The size is an argument to opening rather than a setting of its own because that is the only
   * moment it can be applied: it has to be asked for after the device is open and before the first
   * frame is grabbed. Changing it therefore means opening again.
   */
  void open(int longestEdge) throws Exception;

  /** Frames per second to pace playback at. Falls back to a sane default if unknown. */
  double frameRate();

  /** Total media length, or {@link #DURATION_UNKNOWN} if the source has no timeline. */
  long durationMs();

  /**
   * True for a source that produces frames as they happen rather than from a recording: there is
   * no timeline, so nothing to seek, loop, or pace, and the only frame worth keeping is the newest.
   * {@link FramePipeline} buffers a live source differently for that reason.
   */
  boolean isLive();

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
