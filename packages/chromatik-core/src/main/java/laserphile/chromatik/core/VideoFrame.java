package laserphile.chromatik.core;

import java.awt.image.BufferedImage;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * One decoded video frame as packed ARGB pixels (0xAARRGGBB), row-major, top-left origin.
 * Each frame owns its own array, so frames can be handed between threads without aliasing.
 */
public final class VideoFrame {

  /**
   * FFmpeg reports every time value in microseconds, so anything crossing that boundary in either
   * direction is scaled by this: frame timestamps on the way in, durations and seek targets on the
   * way out.
   */
  public static final long MICROSECONDS_PER_MS = 1000;

  public final int[] argb;
  public final int width;
  public final int height;

  /** Presentation time within the media, straight from the decoder. Restarts at 0 on a loop. */
  public final long mediaTimeMs;

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

  public VideoFrame(int[] argb, int width, int height, long mediaTimeMs) {
    this.argb = argb;
    this.width = width;
    this.height = height;
    this.mediaTimeMs = mediaTimeMs;
  }

  /**
   * Copy a frame handed over by FFmpeg into one of these, or null if it carries no image.
   *
   * Shared by both sources because the conversion is the same whether the pixels came from a file
   * or off the desktop. The converter is caller-owned: it reuses one internal image buffer between
   * calls, so it belongs to a single decode thread. Reading it out to a new int[] here is what
   * gives each frame the private array the rest of this class promises.
   */
  public static VideoFrame from(Frame frame, Java2DFrameConverter converter) {
    final BufferedImage image = converter.convert(frame);
    if (image == null) {
      return null;
    }

    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] argb = image.getRGB(0, 0, width, height, null, 0, width);

    return new VideoFrame(argb, width, height, frame.timestamp / MICROSECONDS_PER_MS);
  }
}
