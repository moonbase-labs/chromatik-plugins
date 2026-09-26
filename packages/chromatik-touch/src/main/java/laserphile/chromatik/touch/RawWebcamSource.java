package laserphile.chromatik.touch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.VideoInputFrameGrabber;

import laserphile.chromatik.core.ColorSpaceCorrection;
import laserphile.chromatik.core.FrameSource;
import laserphile.chromatik.core.VideoFrame;
import laserphile.chromatik.core.WorkingResolution;

import heronarts.lx.LX;

/**
 * Live webcam source that forwards frames as-is, with no blob processing or OSC output.
 */
final class RawWebcamSource implements FrameSource {

  private static final double MAX_CAPTURE_FRAME_RATE = 60;

  private final int cameraIndex;
  private final CaptureBackend backend;
  private final String cameraName;
  private final String inputOverride;
  private final double targetFrameRate;

  private final Java2DFrameConverter converter = new Java2DFrameConverter();

  private FrameGrabber grabber;
  private ColorSpaceCorrection correction = ColorSpaceCorrection.NONE;

  RawWebcamSource(
    int cameraIndex,
    CaptureBackend backend,
    String cameraName,
    String inputOverride,
    double engineFrameRate) {

    this.cameraIndex = cameraIndex;
    this.backend = backend == null ? CaptureBackend.AUTO : backend;
    this.cameraName = cameraName == null ? "" : cameraName.trim();
    this.inputOverride = inputOverride == null ? "" : inputOverride.trim();
    this.targetFrameRate = Math.max(1, Math.min(engineFrameRate, MAX_CAPTURE_FRAME_RATE));
  }

  private enum CaptureDevice {
    AVFOUNDATION,
    DSHOW,
    V4L2
  }

  @Override
  public void open(int longestEdge) throws Exception {
    final CaptureDevice device = detectCaptureDevice();

    if (usesVideoInput(device)) {
      openVideoInput(longestEdge);
      return;
    }

    final String format = formatFor(device);

    Exception lastFailure = null;
    for (OpenAttempt attempt : openAttempts(device)) {
      final FFmpegFrameGrabber opening = new FFmpegFrameGrabber(attempt.input);
      opening.setFormat(format);
      opening.setOption("framerate", String.valueOf(Math.round(this.targetFrameRate)));

      LX.log(String.format("[LaserphileTouchCamera] opening webcam via %s (%s)", format,
        attempt.describe()));

      try {
        opening.start();
        WorkingResolution.applyTo(opening, longestEdge);

        this.correction = ColorSpaceCorrection.forStream(opening, toString());
        this.grabber = opening;
        lastFailure = null;
        break;
      } catch (Exception failure) {
        LX.log(String.format(
          "[LaserphileTouchCamera] webcam open failed (%s): %s",
          attempt.describe(),
          failure));
        lastFailure = failure;
        try {
          opening.release();
        } catch (Exception ignored) {
        }
      }
    }

    if (this.grabber == null) {
      throw new IllegalStateException(
        "Unable to open webcam on Windows DirectShow. Try Refresh, then selecting CamList again. "
          + "If it still fails, close other apps using the camera and check Windows camera privacy "
          + "permissions for Chromatik.",
        lastFailure);
    }

    LX.log(String.format(
      "[LaserphileTouchCamera] webcam open: %dx%d at %.0f fps",
      this.grabber.getImageWidth(),
      this.grabber.getImageHeight(),
      this.targetFrameRate));
  }

  private void openVideoInput(int longestEdge) throws Exception {
    final VideoInputFrameGrabber opening = new VideoInputFrameGrabber(this.cameraIndex);
    opening.setFrameRate(this.targetFrameRate);

    LX.log(String.format(
      "[LaserphileTouchCamera] opening webcam via videoinput (cameraIndex=%d)",
      this.cameraIndex));

    try {
      opening.start();
      WorkingResolution.applyTo(opening, longestEdge);

      this.correction = ColorSpaceCorrection.NONE;
      this.grabber = opening;
    } catch (Exception failure) {
      LX.log(String.format(
        "[LaserphileTouchCamera] webcam open failed (videoinput index=%d): %s",
        this.cameraIndex,
        failure));
      try {
        opening.release();
      } catch (Exception ignored) {
      }
      throw failure;
    }

    LX.log(String.format(
      "[LaserphileTouchCamera] webcam open: %dx%d at %.0f fps",
      this.grabber.getImageWidth(),
      this.grabber.getImageHeight(),
      this.targetFrameRate));
  }

  @Override
  public double frameRate() {
    return this.targetFrameRate;
  }

  @Override
  public long durationMs() {
    return DURATION_UNKNOWN;
  }

  @Override
  public boolean isLive() {
    return true;
  }

  @Override
  public VideoFrame grab() throws Exception {
    final Frame frame = grabImageFrame();
    if (frame == null) {
      return null;
    }

    final VideoFrame captured = VideoFrame.from(frame, this.converter);
    if (captured == null) {
      return null;
    }

    this.correction.applyInPlace(captured.argb);
    return captured;
  }

  private Frame grabImageFrame() throws Exception {
    for (int i = 0; i < 3; i++) {
      final Frame frame = this.grabber.grab();
      if (frame == null || frame.image == null) {
        continue;
      }
      return frame;
    }
    return null;
  }

  @Override
  public void seek(long mediaTimeMs) {
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

  private String inputFor(CaptureDevice device) {
    return switch (device) {
      case AVFOUNDATION -> String.valueOf(this.cameraIndex);
      case V4L2 -> String.format("/dev/video%d", this.cameraIndex);
      case DSHOW -> {
        if (this.cameraName.isBlank()) {
          throw new IllegalStateException(
            "Windows webcam capture requires CamName (for dshow input) or an Input override.");
        }
        yield "video=" + this.cameraName;
      }
    };
  }

  private List<OpenAttempt> openAttempts(CaptureDevice device) {
    if (!this.inputOverride.isBlank()) {
      return List.of(new OpenAttempt(this.inputOverride));
    }

    if (device != CaptureDevice.DSHOW) {
      return List.of(new OpenAttempt(inputFor(device)));
    }

    if (this.cameraName.isBlank()) {
      throw new IllegalStateException(
        "Windows webcam capture requires CamName (for dshow input) or an Input override.");
    }

    final List<OpenAttempt> attempts = new ArrayList<>();
    final String bare = this.cameraName;
    final String quoted = "\"" + bare.replace("\"", "") + "\"";

    attempts.add(new OpenAttempt("video=" + bare));
    attempts.add(new OpenAttempt("video=" + quoted));

    return attempts;
  }

  private boolean usesVideoInput(CaptureDevice device) {
    if (device != CaptureDevice.DSHOW) {
      return false;
    }
    return switch (this.backend) {
      case VIDEOINPUT -> true;
      case AUTO -> this.inputOverride.isBlank();
      case FFMPEG -> false;
    };
  }

  private record OpenAttempt(String input) {
    String describe() {
      return this.input;
    }
  }

  private static String formatFor(CaptureDevice device) {
    return switch (device) {
      case AVFOUNDATION -> "avfoundation";
      case DSHOW -> "dshow";
      case V4L2 -> "v4l2";
    };
  }

  private static CaptureDevice detectCaptureDevice() {
    final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    if (osName.contains("mac")) {
      return CaptureDevice.AVFOUNDATION;
    }

    if (osName.contains("win")) {
      return CaptureDevice.DSHOW;
    }

    if (osName.contains("linux")) {
      return CaptureDevice.V4L2;
    }

    throw new UnsupportedOperationException(
      String.format("no webcam capture device known for this platform (%s)", osName));
  }

  @Override
  public String toString() {
    return String.format(
      "RawWebcamSource(index=%d,backend=%s,name=%s,input=%s)",
      this.cameraIndex,
      this.backend,
      this.cameraName,
      this.inputOverride);
  }
}
