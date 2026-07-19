package laserphile.chromatik.video;

import java.awt.image.BufferedImage;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * A {@link FrameSource} backed by a video file, decoded with JavaCV/FFmpeg.
 *
 * grabImage() only pulls video frames, so the audio track is never decoded (LX has no audio
 * output anyway). Frames are converted to a fresh ARGB int[] per grab.
 */
final class FileVideoSource implements FrameSource {

  private static final double DEFAULT_FRAME_RATE = 30.0;

  private final String path;
  private final Java2DFrameConverter converter = new Java2DFrameConverter();

  private FFmpegFrameGrabber grabber;

  FileVideoSource(String path) {
    this.path = path;
  }

  @Override
  public void open() throws Exception {
    this.grabber = new FFmpegFrameGrabber(this.path);
    this.grabber.start();
  }

  @Override
  public double frameRate() {
    final double reported = this.grabber.getFrameRate();
    return reported > 0 ? reported : DEFAULT_FRAME_RATE;
  }

  @Override
  public VideoFrame grab() throws Exception {
    final Frame frame = this.grabber.grabImage();
    if (frame == null) {
      return null;
    }

    final BufferedImage image = this.converter.convert(frame);
    if (image == null) {
      return null;
    }

    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] argb = image.getRGB(0, 0, width, height, null, 0, width);

    return new VideoFrame(argb, width, height);
  }

  @Override
  public void seekToStart() throws Exception {
    this.grabber.setTimestamp(0);
  }

  @Override
  public void close() {
    if (this.grabber == null) {
      return;
    }

    try {
      this.grabber.stop();
      this.grabber.release();
    } catch (Exception ignored) {
      // Nothing useful to do on close failure; the JVM reclaims native handles on exit.
    }

    this.grabber = null;
  }
}
