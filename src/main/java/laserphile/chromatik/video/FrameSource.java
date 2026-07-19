package laserphile.chromatik.video;

/**
 * A source of decoded video frames. The seam that lets file playback and (later) live screen
 * capture share one decode/projection pipeline. All methods run on the decode thread, never
 * the LX engine thread, so blocking here is fine.
 */
interface FrameSource extends AutoCloseable {

  /** Open the underlying decoder / capture device. */
  void open() throws Exception;

  /** Frames per second to pace playback at. Falls back to a sane default if unknown. */
  double frameRate();

  /** The next decoded frame, or null at end of stream. */
  VideoFrame grab() throws Exception;

  /** Seek back to the start (used for looping). No-op for non-seekable sources. */
  void seekToStart() throws Exception;

  @Override
  void close();
}
