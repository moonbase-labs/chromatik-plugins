package laserphile.chromatik.touch;

import laserphile.chromatik.core.FramePipeline;
import laserphile.chromatik.core.ProjectionControls;
import laserphile.chromatik.core.ProjectionParams;
import laserphile.chromatik.core.VideoFrame;
import laserphile.chromatik.core.WorkingResolution;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.glx.ui.UI2dContainer;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;

/**
 * Webcam-driven camera pattern: projects the live image onto the model.
 */
@LXCategory("Laserphile")
@LXComponent.Name("Touch Camera")
public class TouchCameraPattern extends LXPattern implements UIDeviceControls<TouchCameraPattern> {

  static {
    requireCorePackage();
  }

  private static void requireCorePackage() {
    try {
      Class.forName("laserphile.chromatik.core.FramePipeline");
    } catch (ClassNotFoundException missing) {
      throw new IllegalStateException(
        "The Laserphile Touch Camera package needs the Laserphile Core package, which is not "
          + "installed. Install the chromatik-core jar for your platform into ~/Chromatik/Packages "
          + "and restart Chromatik.",
        missing);
    }
  }

  private static final double FIVE_SECONDS_IN_MS = 5000;
  private static final String NO_CAMERAS_LABEL = "(no cameras found)";
  private static final String[] OUTPUT_OPTIONS = { "Raw", "Proc" };

  public final ProjectionControls projection = new ProjectionControls();

  public final BooleanParameter freeze =
    new BooleanParameter("Freeze", false)
      .setDescription("Hold the current webcam frame");

  public final TriggerParameter calibrate =
    new TriggerParameter("Calibrate")
      .setDescription("Capture the current frame as baseline for subtraction");
  public final CompoundParameter gain =
    new CompoundParameter("Gain", 0.85, 0.1, 2)
      .setDescription("Scale on current frame before subtraction from baseline");
  public final DiscreteParameter threshold =
    new DiscreteParameter("Thresh", 72, 0, 256)
      .setDescription("Binary threshold after subtraction");
  public final BooleanParameter invertDifference =
    new BooleanParameter("Invert", true)
      .setDescription("Use baseline minus current instead of current minus baseline");
  public final BooleanParameter circularMask =
    new BooleanParameter("CircleMask", true)
      .setDescription("Apply a circular processing mask centered on the frame");
  public final CompoundParameter maskRadius =
    new CompoundParameter("MaskRad", 0.95, 0.1, 1)
      .setDescription("Circular mask radius, as a fraction of half the shortest edge");
  public final DiscreteParameter outputMode =
    new DiscreteParameter("Display", OUTPUT_OPTIONS, 1)
      .setDescription("LED image source: raw camera frame or processed calibration mask");

  public final DiscreteParameter cameraDevice =
    new DiscreteParameter("CamList", new String[] { NO_CAMERAS_LABEL }, 0)
      .setDescription("Windows camera list from DirectShow device enumeration");

  public final DiscreteParameter cameraIndex =
    new DiscreteParameter("Camera", 0, 0, 8)
      .setDescription("Camera index used on macOS/Linux unless Input override is set");
  public final BooleanParameter invertX =
    new BooleanParameter("InvertX", false)
      .setDescription("Flip camera input horizontally before processing and projection");
  public final BooleanParameter invertY =
    new BooleanParameter("InvertY", false)
      .setDescription("Flip camera input vertically before processing and projection");
  public final DiscreteParameter backend =
    new DiscreteParameter("Backend", CaptureBackend.OPTIONS, CaptureBackend.defaultOption())
      .setDescription("Capture backend: VideoInput is safer on Windows, FFmpeg uses dshow");
  public final StringParameter cameraName =
    new StringParameter("CamName", "")
      .setDescription("Windows dshow camera name, for example Integrated Camera");
  public final StringParameter inputOverride =
    new StringParameter("Input", "")
      .setDescription("Raw FFmpeg input string override");

  public final DiscreteParameter workingResolution =
    new DiscreteParameter("Res", WorkingResolution.OPTIONS, WorkingResolution.AUTO_OPTION)
      .setDescription("Longest edge to process at; Auto follows the model's point count");

  public final DiscreteParameter previewMode =
    new DiscreteParameter("Preview", TouchPreviewCanvas.OPTIONS, TouchPreviewCanvas.OFF)
      .setDescription("Calibration preview canvas: Off, Raw, Processed, or Both");

  public final TriggerParameter refreshCameras =
    new TriggerParameter("Refresh")
      .setDescription("Rescan Windows camera devices for CamList");

  private final FramePipeline pipeline = new FramePipeline();
  private final TouchVisionConfig visionConfig = new TouchVisionConfig();
  private final TouchVisionProcessor visionProcessor = new TouchVisionProcessor();
  private final TouchPreviewCanvas previewCanvas = new TouchPreviewCanvas("Touch Camera Preview");

  private volatile boolean openRequested = false;
  private volatile boolean syncingCameraList = false;

  private VideoFrame currentFrame = null;

  private double msSinceCaptureOpened = 0;
  private boolean reportedSilentCapture = false;

  public TouchCameraPattern(LX lx) {
    super(lx);

    addParameter("level", this.projection.level);
    addParameters(this.projection.knobParameters);

    addParameter("freeze", this.freeze);
    addParameter("calibrate", this.calibrate);
    addParameter("gain", this.gain);
    addParameter("threshold", this.threshold);
    addParameter("invertDifference", this.invertDifference);
    addParameter("circularMask", this.circularMask);
    addParameter("maskRadius", this.maskRadius);
    addParameter("outputMode", this.outputMode);
    addParameters(this.projection.remainingParameters);

    addParameter("cameraDevice", this.cameraDevice);
    addParameter("cameraIndex", this.cameraIndex);
    addParameter("invertX", this.invertX);
    addParameter("invertY", this.invertY);
    addParameter("backend", this.backend);
    addParameter("cameraName", this.cameraName);
    addParameter("inputOverride", this.inputOverride);
    addParameter("workingResolution", this.workingResolution);
    addParameter("previewMode", this.previewMode);
    addParameter("refreshCameras", this.refreshCameras);

    setRemoteControls(
      this.projection.level,
      this.projection.scale,
      this.projection.scrollX,
      this.projection.scrollY,
      this.projection.yaw,
      this.projection.roll,
      this.projection.gamma,
      this.projection.stretchX,
      this.freeze,
      this.calibrate,
      this.gain,
      this.threshold,
      this.invertDifference,
      this.circularMask,
      this.maskRadius,
      this.outputMode,
      this.cameraDevice,
      this.invertX,
      this.invertY,
      this.backend,
      this.projection.stretchY,
      this.projection.pitch,
      this.projection.translateX,
      this.projection.translateY,
      this.projection.translateZ,
      this.projection.wrapMode,
      this.projection.backgroundMode,
      this.projection.interpolation);

    this.cameraDevice.addListener(parameter -> onCameraDeviceSelected());
    this.cameraIndex.addListener(parameter -> this.openRequested = true);
    this.invertX.addListener(parameter -> this.openRequested = true);
    this.invertY.addListener(parameter -> this.openRequested = true);
    this.backend.addListener(parameter -> this.openRequested = true);
    this.cameraName.addListener(parameter -> this.openRequested = true);
    this.inputOverride.addListener(parameter -> this.openRequested = true);
    this.workingResolution.addListener(parameter -> this.openRequested = true);
    this.previewMode.addListener(parameter -> this.previewCanvas.setMode(this.previewMode.getValuei()));
    this.calibrate.onTrigger(this.visionConfig::requestCalibration);
    this.gain.addListener(parameter -> syncVisionConfig());
    this.threshold.addListener(parameter -> syncVisionConfig());
    this.invertDifference.addListener(parameter -> syncVisionConfig());
    this.circularMask.addListener(parameter -> syncVisionConfig());
    this.maskRadius.addListener(parameter -> syncVisionConfig());
    this.outputMode.addListener(parameter -> this.openRequested = false);
    this.refreshCameras.onTrigger(() -> {
      refreshCameraList();
      this.openRequested = true;
    });

    syncVisionConfig();
    refreshCameraList();
  }

  @Override
  protected void onModelChanged(LXModel model) {
    super.onModelChanged(model);

    if (this.workingResolution.getValuei() == WorkingResolution.AUTO_OPTION) {
      this.openRequested = true;
    }
  }

  private static boolean isWindows() {
    final String osName = System.getProperty("os.name", "").toLowerCase();
    return osName.contains("win");
  }

  private void refreshCameraList() {
    if (!isWindows()) {
      return;
    }

    this.syncingCameraList = true;
    try {
      final String[] priorOptions = this.cameraDevice.getOptions();
      final String[] discovered = WindowsDshowCameraList.listVideoDevices();
      final String currentName = this.cameraName.getString();

      final String[] options;
      if (discovered.length > 0) {
        options = discovered;
      } else if (currentName != null && !currentName.isBlank()) {
        // Keep active capture target visible if enumeration is temporarily unavailable.
        options = new String[] { currentName };
      } else if (priorOptions != null && priorOptions.length > 0 && !priorOptions[0].startsWith("(")) {
        // Preserve previous real device list on transient discovery failures.
        options = priorOptions;
      } else {
        options = new String[] { NO_CAMERAS_LABEL };
      }

      int selected = 0;
      for (int i = 0; i < options.length; i++) {
        if (options[i].equals(currentName)) {
          selected = i;
          break;
        }
      }

      this.cameraDevice.setOptions(options, true);
      this.cameraDevice.setIndex(Math.max(0, Math.min(selected, options.length - 1)));

      if ((currentName == null || currentName.isBlank()) && options.length > 0
        && !options[0].startsWith("(")) {
        this.cameraName.setValue(options[0], false);
      }
    } catch (Throwable t) {
      LX.error(t, "[LaserphileTouch] failed to refresh Windows camera list");
      this.cameraDevice.setOptions(new String[] { NO_CAMERAS_LABEL }, true);
      this.cameraDevice.setIndex(0);
    } finally {
      this.syncingCameraList = false;
    }
  }

  private void onCameraDeviceSelected() {
    if (this.syncingCameraList || !isWindows()) {
      return;
    }

    final String[] options = this.cameraDevice.getOptions();
    if (options == null || options.length == 0) {
      return;
    }

    final int index = Math.max(0, Math.min(this.cameraDevice.getValuei(), options.length - 1));
    final String selected = options[index];
    if (selected.startsWith("(")) {
      return;
    }

    if (!selected.equals(this.cameraName.getString())) {
      this.cameraName.setValue(selected);
    }
    if (index != this.cameraIndex.getValuei()) {
      this.cameraIndex.setValue(index);
    }
    this.openRequested = true;
  }

  private void openCapture() {
    this.pipeline.stop();

    this.currentFrame = null;
    this.msSinceCaptureOpened = 0;
    this.reportedSilentCapture = false;

    syncVisionConfig();

    final double engineFrameRate = this.lx.engine.framesPerSecond.getValue();

    this.pipeline.start(
      new RawWebcamSource(
        this.cameraIndex.getValuei(),
        CaptureBackend.fromIndex(this.backend.getValuei()),
        this.cameraName.getString(),
        this.inputOverride.getString(),
        this.invertX.isOn(),
        this.invertY.isOn(),
        engineFrameRate),
      WorkingResolution.edgeFor(this.workingResolution.getValuei(), this.model.size));
  }

  private void syncVisionConfig() {
    this.visionConfig.currentGain = this.gain.getValue();
    this.visionConfig.threshold = this.threshold.getValuei();
    this.visionConfig.circularMaskEnabled = this.circularMask.isOn();
    this.visionConfig.maskRadiusNormalized = this.maskRadius.getValue();

    // Touch Camera only needs mask+calibrate controls; keep remaining vision defaults stable.
    this.visionConfig.invertDifference = this.invertDifference.isOn();
    this.visionConfig.overlayBlobs = false;
    this.visionConfig.oscEnabled = false;
  }

  @Override
  protected void onActive() {
    refreshCameraList();
    this.previewCanvas.setMode(this.previewMode.getValuei());
    this.openRequested = false;
    openCapture();
  }

  @Override
  protected void onInactive() {
    this.previewCanvas.setMode(TouchPreviewCanvas.OFF);
    this.pipeline.stop();
  }

  @Override
  public void dispose() {
    this.previewCanvas.close();
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

    if (!this.freeze.isOn()) {
      final VideoFrame latest = this.pipeline.latestFrame();
      if (latest != null) {
        final VideoFrame processed = this.visionProcessor.process(latest, this.visionConfig).frame();
        this.currentFrame = (this.outputMode.getValuei() == 0) ? latest : processed;
        this.previewCanvas.update(latest, processed);
      }
    }

    if (this.currentFrame == null) {
      setColors(ProjectionParams.backgroundColor(this.projection.backgroundMode.getEnum()));
      return;
    }

    this.projection.project(this.currentFrame, this.model, this.colors);
  }

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
      "[LaserphileTouch] webcam capture has produced no frames. Check camera permissions and input "
        + "selection (Camera/CamName/Input), then toggle the pattern off and on to retry.");
  }

  private static final float PANEL_COLUMN_WIDTH = 76;

  /** Custom panel with consistent camera/vision ordering and constrained per-column density. */
  @Override
  public void buildDeviceControls(LXStudio.UI ui, UIDevice device, TouchCameraPattern pattern) {
    device.setLayout(UI2dContainer.Layout.HORIZONTAL, 4);
    device.setChildSpacing(6);

    if (isWindows()) {
      addColumn(device, PANEL_COLUMN_WIDTH, "Source",
        newDropMenu(pattern.cameraDevice, PANEL_COLUMN_WIDTH),
        newButton(pattern.refreshCameras, PANEL_COLUMN_WIDTH),
        newDropMenu(pattern.previewMode, PANEL_COLUMN_WIDTH),
        newDropMenu(pattern.backend, PANEL_COLUMN_WIDTH),
        newDropMenu(pattern.workingResolution, PANEL_COLUMN_WIDTH));
    } else {
      addColumn(device, PANEL_COLUMN_WIDTH, "Source",
        newIntegerBox(pattern.cameraIndex, PANEL_COLUMN_WIDTH),
        newDropMenu(pattern.previewMode, PANEL_COLUMN_WIDTH),
        newDropMenu(pattern.backend, PANEL_COLUMN_WIDTH),
        newDropMenu(pattern.workingResolution, PANEL_COLUMN_WIDTH));
    }

    addColumn(device, PANEL_COLUMN_WIDTH, "Orient",
      newButton(pattern.invertX, PANEL_COLUMN_WIDTH),
      newButton(pattern.invertY, PANEL_COLUMN_WIDTH));

    addColumn(device, PANEL_COLUMN_WIDTH, "Vision",
      newDropMenu(pattern.outputMode, PANEL_COLUMN_WIDTH),
      newButton(pattern.freeze, PANEL_COLUMN_WIDTH),
      newButton(pattern.calibrate, PANEL_COLUMN_WIDTH),
      newButton(pattern.invertDifference, PANEL_COLUMN_WIDTH),
      newButton(pattern.circularMask, PANEL_COLUMN_WIDTH));

    addColumn(device, PANEL_COLUMN_WIDTH, "Mask",
      newKnob(pattern.gain),
      newKnob(pattern.threshold),
      newKnob(pattern.maskRadius));

    addColumn(device, PANEL_COLUMN_WIDTH, "Move",
      newKnob(pattern.projection.translateX),
      newKnob(pattern.projection.translateY),
      newKnob(pattern.projection.translateZ));

    addColumn(device, PANEL_COLUMN_WIDTH, "Rotate",
      newKnob(pattern.projection.yaw),
      newKnob(pattern.projection.pitch),
      newKnob(pattern.projection.roll));

    addColumn(device, PANEL_COLUMN_WIDTH, "Tone",
      newKnob(pattern.projection.level),
      newKnob(pattern.projection.gamma),
      newDropMenu(pattern.projection.backgroundMode, PANEL_COLUMN_WIDTH));

    addColumn(device, PANEL_COLUMN_WIDTH, "Frame",
      newKnob(pattern.projection.scale),
      newKnob(pattern.projection.stretchX),
      newKnob(pattern.projection.stretchY));

    addColumn(device, PANEL_COLUMN_WIDTH, "Pan",
      newKnob(pattern.projection.scrollX),
      newKnob(pattern.projection.scrollY));

    addColumn(device, PANEL_COLUMN_WIDTH, "Sample",
      newDropMenu(pattern.projection.wrapMode, PANEL_COLUMN_WIDTH),
      newDropMenu(pattern.projection.interpolation, PANEL_COLUMN_WIDTH));
  }
}
