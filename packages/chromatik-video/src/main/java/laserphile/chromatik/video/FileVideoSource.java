package laserphile.chromatik.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import laserphile.chromatik.core.ColorSpaceCorrection;
import laserphile.chromatik.core.FrameSource;
import laserphile.chromatik.core.VideoFrame;
import laserphile.chromatik.core.WorkingResolution;

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
  private ColorSpaceCorrection correction = ColorSpaceCorrection.NONE;

  FileVideoSource(String path) {
    this.path = path;
  }

  /**
   * The field is only assigned once the grabber has actually started.
   *
   * Assigning it first looks equivalent and is not: a grabber that failed to start is not null,
   * it is a live object wrapping a null native context, so the guards below would wave it through
   * and the next call would come back as a null-pointer dereference inside FFmpeg rather than as
   * the real reason the file could not be opened.
   */
  @Override
  public void open(int longestEdge) throws Exception {
    final FFmpegFrameGrabber opening = new FFmpegFrameGrabber(this.path);
    opening.start();

    WorkingResolution.applyTo(opening, longestEdge);

    this.correction = ColorSpaceCorrection.forStream(opening, this.path);
    this.grabber = opening;
  }

  // Both of the following are asked immediately after open() succeeds, so the grabber is normally
  // there. They still answer when it is not, because a failed open leaves it null and an exception
  // on the way out of one of these would replace the real reason the open failed.

  @Override
  public double frameRate() {
    if (this.grabber == null) {
      return DEFAULT_FRAME_RATE;
    }

    final double reported = this.grabber.getFrameRate();
    return reported > 0 ? reported : DEFAULT_FRAME_RATE;
  }

  @Override
  public long durationMs() {
    if (this.grabber == null) {
      return DURATION_UNKNOWN;
    }

    final long lengthMicroseconds = this.grabber.getLengthInTime();

    return lengthMicroseconds > 0
      ? lengthMicroseconds / VideoFrame.MICROSECONDS_PER_MS
      : DURATION_UNKNOWN;
  }

  @Override
  public boolean isLive() {
    return false;
  }

  @Override
  public VideoFrame grab() throws Exception {
    final Frame frame = this.grabber.grabImage();
    if (frame == null) {
      return null;
    }

    final VideoFrame decoded = VideoFrame.from(frame, this.converter);

    if (decoded != null) {
      this.correction.applyInPlace(decoded.argb);
    }

    return decoded;
  }

  @Override
  public void seek(long mediaTimeMs) throws Exception {
    // Video stream only: the audio stream is never decoded, so seeking it would be wasted work.
    this.grabber.setVideoTimestamp(Math.max(0, mediaTimeMs) * VideoFrame.MICROSECONDS_PER_MS);
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

  /** Log lines are the only place a source has to name itself. */
  @Override
  public String toString() {
    return this.path;
  }
}
