package laserphile.chromatik.screen;

import laserphile.chromatik.core.FramePipeline;
import laserphile.chromatik.core.ProjectionControls;
import laserphile.chromatik.core.ProjectionParams;
import laserphile.chromatik.core.VideoFrame;
import laserphile.chromatik.core.WorkingResolution;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Projects the live desktop onto the LX model, through the same projection controls as a video.
 *
 * A background thread captures the screen and keeps only the newest frame; run() takes whatever is
 * there and projects it. There is no timeline, so none of the transport controls a video has would
 * mean anything here, which is why this is a pattern of its own rather than a mode of that one:
 * the auto-generated panel cannot hide a control, so a shared pattern would show a playhead and a
 * speed control that do nothing.
 *
 * Capturing the screen needs the operating system's permission, and Chromatik has to be the
 * application holding it. Without it the capture device opens and then waits forever on a first
 * frame that never comes, so the pattern renders black; {@link #watchForSilentCapture} is what
 * explains that in the log.
 *
 * The projection and decode machinery lives in the separate Laserphile Core package, which has to
 * be installed alongside this one.
 */
@LXCategory("Laserphile")
@LXComponent.Name("Screen Capture")
public class ScreenCapturePattern extends LXPattern {

  /**
   * Chromatik cannot express a dependency between packages, so nothing stops this one being
   * installed on its own. Without the core package the first thing to touch it fails with a bare
   * NoClassDefFoundError naming an internal class, which says nothing about what to do. Checking
   * here turns that into a sentence.
   *
   * A static block runs at class initialisation, which happens when the pattern is first added to
   * a channel rather than when Chromatik scans the jar, so the pattern still lists normally and
   * only explains itself when someone reaches for it.
   */
  static {
    requireCorePackage();
  }

  private static void requireCorePackage() {
    try {
      Class.forName("laserphile.chromatik.core.FramePipeline");
    } catch (ClassNotFoundException missing) {
      throw new IllegalStateException(
        "The Laserphile Screen Capture package needs the Laserphile Core package, which is not "
          + "installed. Install the chromatik-core jar for your platform into ~/Chromatik/Packages "
          + "and restart Chromatik.",
        missing);
    }
  }

  /**
   * How long the capture may produce nothing before the log says so. A device that opens but never
   * delivers a frame is what a missing screen-recording permission looks like from here, and with
   * no custom UI the log is the only place that can explain a black pattern.
   */
  private static final double FIVE_SECONDS_IN_MS = 5000;

  /** Orientation, scale, wrapping, sampling and brightness, shared with the other patterns. */
  public final ProjectionControls projection = new ProjectionControls();

  public final BooleanParameter freeze =
    new BooleanParameter("Freeze", false)
      .setDescription("Hold the current frame instead of following the screen");

  // Which display to grab, for a machine with more than one. Only macOS and Linux can pick: the
  // Windows capture device covers the whole virtual desktop and has no per-display index.
  public final DiscreteParameter screen =
    new DiscreteParameter("Screen", 0, 0, 4)
      .setDescription("Which display to capture (ignored on Windows)");
  public final BooleanParameter cursor =
    new BooleanParameter("Cursor", true).setDescription("Include the mouse pointer");
  public final DiscreteParameter workingResolution =
    new DiscreteParameter("Res", WorkingResolution.OPTIONS, WorkingResolution.AUTO_OPTION)
      .setDescription("Longest edge to capture at; Auto follows the model's point count");

  private final FramePipeline pipeline = new FramePipeline();

  // Parameter listeners can fire on the UI thread, so they only raise a flag; run() acts on it.
  private volatile boolean openRequested = false;

  // Held so Freeze has something to keep showing, and so a dropped capture holds its last frame
  // rather than blinking to black.
  private VideoFrame currentFrame = null;

  // How long the capture has gone without producing a frame, and whether that has already been
  // reported, so a wedged device says so once rather than every render.
  private double msSinceCaptureOpened = 0;
  private boolean reportedSilentCapture = false;

  public ScreenCapturePattern(LX lx) {
    super(lx);

    // The panel is drawn in this order, and it matches the remote control order below so that the
    // eight knobs of a control surface line up with the panel's top row.
    addParameter("level", this.projection.level);
    // Nothing of this pattern's own takes a knob, so all seven of these land on knobs 2 to 8.
    addParameters(this.projection.knobParameters);

    addParameter("freeze", this.freeze);
    addParameters(this.projection.remainingParameters);

    // Held back from the surface, so last. Anything surface-less registered earlier would offset
    // every control after it and break the panel's match with the knobs.
    addParameter("screen", this.screen);
    addParameter("cursor", this.cursor);
    addParameter("workingResolution", this.workingResolution);

    setRemoteControls(
      // A MIDI surface binds its eight device knobs to the first eight entries here, so all eight
      // are continuous controls. An APC40 cannot page past its eighth knob, so a button or a
      // trigger in this range costs a knob outright.
      this.projection.level,
      this.projection.scale,
      this.projection.scrollX,
      this.projection.scrollY,
      this.projection.yaw,
      this.projection.pitch,
      this.projection.roll,
      this.projection.stretchX,
      // Past the eighth knob. Still mappable by hand, just not picked up by a surface. This order
      // matches the panel, so anything inserted here has to be inserted there too.
      this.freeze,
      this.projection.stretchY,
      this.projection.translateX,
      this.projection.translateY,
      this.projection.translateZ,
      this.projection.wrapMode,
      this.projection.backgroundMode,
      this.projection.interpolation);
    // Screen, Cursor and Res stay out of the list entirely. All three are read when the capture
    // device opens, so changing any of them reopens it, and a screen device can take seconds to
    // open. They are discrete controls a surface could happily bind, which is exactly why the
    // exclusion is deliberate: swept from a knob, they would thrash the device.

    this.screen.addListener(parameter -> this.openRequested = true);
    this.cursor.addListener(parameter -> this.openRequested = true);
    this.workingResolution.addListener(parameter -> this.openRequested = true);
  }

  /**
   * A new model means a new point count, and on Auto that is what sets the capture size, so the
   * device has to be opened again to take effect. Any fixed size is unaffected.
   */
  @Override
  protected void onModelChanged(LXModel model) {
    super.onModelChanged(model);

    if (this.workingResolution.getValuei() == WorkingResolution.AUTO_OPTION) {
      this.openRequested = true;
    }
  }

  private void openCapture() {
    this.pipeline.stop();

    this.currentFrame = null;
    this.msSinceCaptureOpened = 0;
    this.reportedSilentCapture = false;

    // The engine's frame rate is the capture rate: there is no point grabbing the screen faster
    // than the renderer consumes it, and every extra frame is a full colour conversion. It is read
    // here rather than watched, because it is a slider and reopening the device per drag increment
    // would stall the capture for as long as the drag lasted. A new rate applies at the next open.
    final double engineFrameRate = this.lx.engine.framesPerSecond.getValue();

    this.pipeline.start(
      new ScreenCaptureSource(this.screen.getValuei(), engineFrameRate, this.cursor.isOn()),
      WorkingResolution.edgeFor(this.workingResolution.getValuei(), this.model.size));
  }

  @Override
  protected void onActive() {
    this.openRequested = false;
    openCapture();
  }

  @Override
  protected void onInactive() {
    this.pipeline.stop();
  }

  @Override
  public void dispose() {
    this.pipeline.stop();
    super.dispose();
  }

  @Override
  protected void run(double deltaMs) {
    if (this.openRequested) {
      this.openRequested = false;
      openCapture();
    }

    watchForSilentCapture(deltaMs);

    // Freeze simply stops collecting. The capture thread keeps running underneath, deliberately:
    // closing the device would make unfreezing cost the seconds an open takes, and this control
    // has to be instant.
    if (!this.freeze.isOn()) {
      this.currentFrame = this.pipeline.latestFrame();
    }

    if (this.currentFrame == null) {
      // Nothing captured yet, or nothing ever: a device still opening, or one the operating system
      // never granted. The Background control says what should show through.
      setColors(ProjectionParams.backgroundColor(this.projection.backgroundMode.getEnum()));
      return;
    }

    this.projection.project(this.currentFrame, this.model, this.colors);
  }

  /**
   * Say something, once, if the capture has been open a while and has still produced nothing.
   *
   * That is the shape a refused screen-recording permission takes: the device opens, then waits on
   * a first frame the operating system never sends, and the pattern renders black with no other
   * clue as to why. FFmpeg cannot be made to give up on that wait, so the log line is the whole
   * remedy.
   */
  private void watchForSilentCapture(double deltaMs) {
    if (this.reportedSilentCapture || this.pipeline.hasPublishedFrame()) {
      return;
    }

    this.msSinceCaptureOpened += deltaMs;

    if (this.msSinceCaptureOpened < FIVE_SECONDS_IN_MS) {
      return;
    }

    this.reportedSilentCapture = true;

    LX.log(
      "[LaserphileScreen] screen capture has produced no frames. Chromatik most likely lacks screen "
        + "recording permission: grant it in System Settings > Privacy & Security > Screen & System "
        + "Audio Recording, then restart Chromatik.");
  }
}
