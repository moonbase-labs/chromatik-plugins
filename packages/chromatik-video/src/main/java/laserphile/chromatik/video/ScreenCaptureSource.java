package laserphile.chromatik.video;

import java.util.Locale;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import heronarts.lx.LX;

/**
 * A live {@link FrameSource} that grabs the desktop through FFmpeg's screen-capture input device.
 *
 * FFmpeg does the capturing rather than {@code java.awt.Robot}, which would be the shorter route
 * but is not safe here: Chromatik launches with {@code -XstartOnFirstThread} so that GLFW can own
 * the main thread, and Robot forces up the macOS AWT toolkit, which wants that same thread for
 * AppKit's run loop. Going through FFmpeg keeps the capture off any toolkit, and the frames arrive
 * in the same shape as a decoded file, so the projection stage needs no changes at all.
 *
 * <p><b>Screen recording permission.</b> Every desktop platform gates this, and macOS gates it
 * hardest: with permission missing, FFmpeg opens the device and then waits forever for a first
 * frame that the OS will never deliver. That wait cannot be bounded from here, because the device
 * blocks inside its own header read where the grabber's timeout does not reach. It is survivable
 * only because {@link #open()} runs on the capture thread, which is a daemon: a wedged capture
 * shows black and leaves the rest of the app alone. {@link FramePipeline} is what notices the
 * silence and says so in the log.
 */
final class ScreenCaptureSource implements FrameSource {

  /**
   * Longest side of a captured frame. A Retina desktop runs to about 3000x2000, which is 24 MB of
   * pixels every frame and far more detail than a few thousand LEDs can show; capping it keeps the
   * per-frame cost near a video file's. swscale does the shrink as part of the colour conversion
   * that has to happen anyway, so it is close to free. The user-facing working-resolution control
   * is a later milestone; this is just a ceiling that keeps live capture affordable.
   */
  private static final int MAX_CAPTURE_EDGE = 480;

  /** The desktop has no natural frame rate, so the capture rate is also the reported one. */
  private final double targetFrameRate;
  private final int screenIndex;

  private FFmpegFrameGrabber grabber;
  private final Java2DFrameConverter converter = new Java2DFrameConverter();

  ScreenCaptureSource(int screenIndex, double targetFrameRate) {
    this.screenIndex = screenIndex;
    this.targetFrameRate = targetFrameRate;
  }

  /** The FFmpeg input device that grabs the desktop, one per desktop platform. */
  private enum CaptureDevice {
    /** macOS. Each display is a video device named "Capture screen N", alongside the cameras. */
    AVFOUNDATION,
    /**
     * Windows. A single "desktop" device covering the whole virtual desktop; there is no per-display
     * index, so a multi-monitor setup captures the lot and the projection controls crop it.
     */
    GDIGRAB,
    /** Linux under X11. Screen N of display 0 is ":0.N". A Wayland session has no X screen to grab. */
    X11GRAB
  }

  @Override
  public void open() throws Exception {
    final CaptureDevice device = detectCaptureDevice();

    this.grabber = new FFmpegFrameGrabber(inputFor(device));
    this.grabber.setFormat(formatFor(device));
    this.grabber.setOption("framerate", String.valueOf(Math.round(this.targetFrameRate)));
    this.grabber.setOption(cursorOptionFor(device), "1");

    LX.log(String.format(
      "[LaserphileVideo] opening screen capture via %s (%s). This needs screen recording "
        + "permission for Chromatik; if the pattern stays black, grant it and restart Chromatik.",
      formatFor(device), inputFor(device)));

    this.grabber.start();

    capFrameSize();

    LX.log(String.format("[LaserphileVideo] screen capture open: %dx%d at %.0f fps",
      this.grabber.getImageWidth(), this.grabber.getImageHeight(), this.targetFrameRate));
  }

  /**
   * Shrink the frames to something an LED model can use, preserving the display's aspect ratio.
   *
   * The size is set after opening rather than before on purpose. Asking for a size up front makes
   * the grabber pass it to the device as a requested capture mode, which a screen cannot satisfy,
   * and it would also mean guessing the display's shape. Once the device is open its real size is
   * known, and setting the size before the first grab still reaches swscale in time to be the size
   * the frames come out at.
   */
  private void capFrameSize() {
    final int nativeWidth = this.grabber.getImageWidth();
    final int nativeHeight = this.grabber.getImageHeight();
    final int longestEdge = Math.max(nativeWidth, nativeHeight);

    if (longestEdge <= MAX_CAPTURE_EDGE) {
      return;
    }

    final double shrink = MAX_CAPTURE_EDGE / (double) longestEdge;

    this.grabber.setImageWidth(Math.max(1, (int) Math.round(nativeWidth * shrink)));
    this.grabber.setImageHeight(Math.max(1, (int) Math.round(nativeHeight * shrink)));
  }

  private static CaptureDevice detectCaptureDevice() throws Exception {
    final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    if (osName.contains("mac")) {
      return CaptureDevice.AVFOUNDATION;
    }

    if (osName.contains("win")) {
      return CaptureDevice.GDIGRAB;
    }

    if (osName.contains("linux")) {
      return CaptureDevice.X11GRAB;
    }

    throw new UnsupportedOperationException(
      String.format("no screen capture device known for this platform (%s)", osName));
  }

  private static String formatFor(CaptureDevice device) {
    return switch (device) {
      case AVFOUNDATION -> "avfoundation";
      case GDIGRAB -> "gdigrab";
      case X11GRAB -> "x11grab";
    };
  }

  private String inputFor(CaptureDevice device) {
    return switch (device) {
      // By name rather than by device index: the index counts cameras too, so it shifts when a
      // webcam is plugged in, whereas the name is stable.
      case AVFOUNDATION -> String.format("Capture screen %d", this.screenIndex);
      case GDIGRAB -> "desktop";
      case X11GRAB -> String.format("%s.%d", x11DisplayWithoutScreen(), this.screenIndex);
    };
  }

  /** gdigrab spells the same option differently to the other two. */
  private static String cursorOptionFor(CaptureDevice device) {
    return switch (device) {
      case AVFOUNDATION -> "capture_cursor";
      case GDIGRAB, X11GRAB -> "draw_mouse";
    };
  }

  /**
   * The display half of DISPLAY, with any screen number dropped so this class can supply its own.
   * DISPLAY holds "host:display" optionally followed by ".screen", for example ":0" or ":1.0".
   */
  private static String x11DisplayWithoutScreen() {
    final String display = System.getenv("DISPLAY");

    if (display == null || display.isBlank()) {
      return ":0";
    }

    final int screenSeparator = display.lastIndexOf('.');
    final int displaySeparator = display.lastIndexOf(':');

    return screenSeparator > displaySeparator ? display.substring(0, screenSeparator) : display;
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
    final Frame frame = this.grabber.grabImage();
    if (frame == null) {
      return null;
    }

    return VideoFrame.from(frame, this.converter);
  }

  /** Nothing to seek: the desktop only ever has a present. */
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

  /** Log lines are the only place a source has to name itself. */
  @Override
  public String toString() {
    return String.format("screen %d", this.screenIndex);
  }
}
