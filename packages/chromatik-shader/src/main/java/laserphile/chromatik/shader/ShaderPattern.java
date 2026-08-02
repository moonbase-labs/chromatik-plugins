package laserphile.chromatik.shader;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import laserphile.chromatik.core.FramePipeline;
import laserphile.chromatik.core.ProjectionControls;
import laserphile.chromatik.core.ProjectionParams;
import laserphile.chromatik.core.VideoFrame;
import laserphile.chromatik.core.WorkingResolution;

import heronarts.glx.GLX;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Renders a GLSL fragment shader onto the LX model, through the same projection controls as a
 * video.
 *
 * A background thread owns an offscreen OpenGL context, draws the shader into a framebuffer and
 * reads it back; run() takes the newest frame and projects it. Structurally this is the Screen
 * Capture pattern with a GPU where the capture device was: same live single-slot buffering, same
 * absence of a timeline. The one addition is that the engine drives the shader's clock, so Speed
 * changes how fast it evolves without touching how often it renders.
 *
 * A chosen file is watched, so saving an edit in an editor is all it takes to see the change. A
 * shader that will not compile leaves the previous one running and reports why, on the theory
 * that a typo should cost the edit rather than the output.
 *
 * The projection and pipeline machinery lives in the separate Laserphile Core package, which has
 * to be installed alongside this one.
 */
@LXCategory("Laserphile")
@LXComponent.Name("Shader")
public class ShaderPattern extends LXPattern {

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
        "The Laserphile Shader package needs the Laserphile Core package, which is not installed. "
          + "Install the chromatik-core jar for your platform into ~/Chromatik/Packages and "
          + "restart Chromatik.",
        missing);
    }
  }

  private static final double MILLISECONDS_PER_SECOND = 1000;

  /** Offered by the file chooser. The contents decide what a file is, not the suffix. */
  private static final String[] SHADER_EXTENSIONS = { "glsl", "frag", "fs", "fsh" };

  /**
   * Folder the last browse landed in. Shared by every Shader pattern and kept for the run of the
   * app, so a freshly added pattern opens the chooser where the previous one finished instead of
   * back at the media folder. Written from the dialog callback, read when the next dialog opens.
   */
  private static volatile String lastBrowsedFolder = null;

  private final LX lx;

  // Empty until the user picks something, which renders a built-in shader instead. A default
  // pointing at a specific file would be a file-not-found for everyone without that file.
  public final StringParameter fileName =
    new StringParameter("File", "")
      .setDescription("Shader file: an absolute path, or a path relative to ~/Chromatik");
  public final TriggerParameter browse =
    new TriggerParameter("Browse").setDescription("Pick a .glsl fragment shader");
  public final TriggerParameter reload =
    new TriggerParameter("Reload").setDescription("Read and compile the shader again");

  /**
   * Why the last read did not take, or empty when the running shader is the one on disk. Written
   * only by run(), and registered so a custom panel has something to draw.
   */
  public final StringParameter error =
    new StringParameter("Error", "").setDescription("Compiler output from the last failed load");

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0, 4).setExponent(2)
      .setDescription("How fast the shader's clock advances: 1 is real time");
  public final BooleanParameter play =
    new BooleanParameter("Play", true).setDescription("Advance the shader's clock");

  public final DiscreteParameter workingResolution =
    new DiscreteParameter("Res", WorkingResolution.OPTIONS, WorkingResolution.AUTO_OPTION)
      .setDescription("Edge length to render at; Auto follows the model's point count");

  /** Orientation, scale, wrapping, sampling and brightness, shared with the other patterns. */
  public final ProjectionControls projection = new ProjectionControls();

  private final FramePipeline pipeline = new FramePipeline();

  /** Held so the engine can push the clock at it and read compile results back. */
  private ShaderSource source = null;

  /**
   * The shader's own clock, in milliseconds, advanced by run() rather than read off the wall.
   * Driving it here rather than in the renderer is what lets Speed scale it and Play hold it
   * without the render thread knowing either control exists.
   */
  private double elapsedMs = 0;

  // Parameter listeners can fire on the UI thread, so they only raise a flag; run() acts on it.
  private volatile boolean openRequested = false;

  /** Which load the panel has already been told about, so each one is reported once. */
  private int reportedLoadCount = 0;

  public ShaderPattern(LX lx) {
    super(lx);
    this.lx = lx;

    // The panel is drawn in this order, and it matches the remote control order below so that the
    // eight knobs of a control surface line up with the panel's top row.
    addParameter("level", this.projection.level);
    addParameter("speed", this.speed);
    // Speed has taken a knob, so the last of these lands one past the knob row.
    addParameters(this.projection.knobParameters);

    addParameter("play", this.play);
    addParameters(this.projection.remainingParameters);

    // Everything below is held back from the surface, so it goes last. Anything surface-less
    // added earlier would offset every control after it and break the panel's match with the
    // knobs.
    addParameter("workingResolution", this.workingResolution);
    addParameter("browse", this.browse);
    addParameter("reload", this.reload);

    // Neither draws a control in the default panel, which only renders numbers, switches and
    // dropdowns. They are registered so the chosen path is saved with the project, and so a
    // custom panel has them to read.
    addParameter("file", this.fileName);
    addParameter("error", this.error);

    setRemoteControls(
      // A MIDI surface binds its eight device knobs to the first eight entries here, so all eight
      // are continuous controls. An APC40 cannot page past its eighth knob, so a button or a
      // trigger in this range costs a knob outright.
      this.projection.level,
      this.speed,
      this.projection.scale,
      this.projection.scrollX,
      this.projection.scrollY,
      this.projection.yaw,
      this.projection.pitch,
      this.projection.roll,
      // Past the eighth knob. Still mappable by hand, just not picked up by a surface. This order
      // matches the panel, so anything inserted here has to be inserted there too.
      this.projection.stretchX,
      this.play,
      this.projection.stretchY,
      this.projection.translateX,
      this.projection.translateY,
      this.projection.translateZ,
      this.projection.wrapMode,
      this.projection.backgroundMode,
      this.projection.interpolation);
    // Browse, Reload and Res stay out of the list. The first two read a file from disk, which is
    // not something to hand to a control surface, and Res tears the context down and builds
    // another. All three are the tail of the panel, so everything ahead of them lines up.

    this.fileName.addListener(parameter -> this.openRequested = true);
    this.workingResolution.addListener(parameter -> this.openRequested = true);
    this.browse.onTrigger(this::showFileChooser);
    this.reload.onTrigger(() -> this.openRequested = true);
  }

  private void openRenderer() {
    this.pipeline.stop();
    this.source = null;
    this.reportedLoadCount = 0;

    // The engine's frame rate is the render rate: there is no point drawing faster than the
    // renderer consumes, and every extra frame is a full readback off the GPU. Read here rather
    // than watched, because reopening per drag increment would stall rendering for the drag.
    final double engineFrameRate = this.lx.engine.framesPerSecond.getValue();
    final ShaderSource opening = new ShaderSource(chosenFile(), engineFrameRate);

    this.source = opening;
    this.pipeline.start(
      opening, WorkingResolution.edgeFor(this.workingResolution.getValuei(), this.model.size));
  }

  /**
   * The shader to render, or null for the built-in one.
   *
   * A path that points at nothing is reported here rather than left to the loader, so that a
   * mistyped path and a project opened on a machine without the file both say so plainly.
   */
  private File chosenFile() {
    final String resolved = resolvePath(this.fileName.getString());

    if (resolved == null) {
      return null;
    }

    final File file = new File(resolved);

    if (!file.isFile()) {
      LX.log(String.format("[LaserphileShader] shader file not found: %s", resolved));
      return null;
    }

    return file;
  }

  /**
   * Open the desktop file chooser.
   *
   * The dialog belongs to GLX, the windowed build of LX, and GLX extends LX, so the same object
   * the pattern was handed at construction is the one that can show it. Under a plain headless
   * LX there is no window and no dialog, hence the check.
   */
  private void showFileChooser() {
    if (!(this.lx instanceof GLX glx)) {
      LX.log("[LaserphileShader] the file chooser needs the Chromatik desktop app");
      return;
    }

    glx.showOpenFileDialog(
      "Open Shader",
      "GLSL Shader",
      SHADER_EXTENSIONS,
      chooserStartPath(),
      this::onFileChosen);
  }

  /**
   * Where the chooser opens: the folder holding the current shader, else the folder the last
   * browse landed in, else the Chromatik media folder.
   *
   * The dialog reads whatever follows the final separator as a file name and opens the folder
   * above it, so a folder has to be handed over with its trailing separator kept.
   */
  private String chooserStartPath() {
    final File currentFolder = parentFolderOf(resolvePath(this.fileName.getString()));

    if (currentFolder != null) {
      return asFolderPath(currentFolder.getAbsolutePath());
    }

    if (lastBrowsedFolder != null) {
      return asFolderPath(lastBrowsedFolder);
    }

    return asFolderPath(this.lx.getMediaPath());
  }

  /** The folder holding the given file, or null when that folder is not on disk. */
  private static File parentFolderOf(String path) {
    if (path == null) {
      return null;
    }

    final File parent = new File(path).getParentFile();

    return (parent != null && parent.isDirectory()) ? parent : null;
  }

  private static String asFolderPath(String folder) {
    if (folder == null || folder.isBlank()) {
      return folder;
    }

    return folder.endsWith(File.separator) ? folder : folder.concat(File.separator);
  }

  private void onFileChosen(String chosenPath) {
    if (chosenPath == null || chosenPath.isBlank()) {
      return; // the user cancelled
    }

    lastBrowsedFolder = new File(chosenPath).getParent();

    this.fileName.setValue(relativizeToMediaFolder(chosenPath));
  }

  /**
   * Store a path under ~/Chromatik as a relative one, so a project shared with someone else
   * still finds the shader next to their own Chromatik folder. Anything outside stays absolute,
   * because there is nothing portable to say about it.
   */
  private String relativizeToMediaFolder(String chosenPath) {
    final String mediaPath = this.lx.getMediaPath();

    if (mediaPath == null || mediaPath.isBlank()) {
      return chosenPath;
    }

    final Path mediaFolder = Paths.get(mediaPath).toAbsolutePath().normalize();
    final Path chosen = Paths.get(chosenPath).toAbsolutePath().normalize();

    if (!chosen.startsWith(mediaFolder)) {
      return chosenPath;
    }

    return mediaFolder.relativize(chosen).toString();
  }

  /** Absolute paths are used as-is; relative paths resolve under ~/Chromatik for portability. */
  private String resolvePath(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }

    File file = new File(raw);
    if (!file.isAbsolute()) {
      file = this.lx.getMediaFile(raw);
    }

    return file.getAbsolutePath();
  }

  /**
   * A new model means a new point count, and on Auto that is what sets the render size, so the
   * renderer has to be opened again to take effect. Any fixed size is unaffected.
   */
  @Override
  protected void onModelChanged(LXModel model) {
    super.onModelChanged(model);

    if (this.workingResolution.getValuei() == WorkingResolution.AUTO_OPTION) {
      this.openRequested = true;
    }
  }

  @Override
  protected void onActive() {
    this.openRequested = false;
    openRenderer();
  }

  @Override
  protected void onInactive() {
    this.pipeline.stop();
    this.source = null;
  }

  @Override
  public void dispose() {
    this.pipeline.stop();
    this.source = null;
    super.dispose();
  }

  @Override
  protected void run(double deltaMs) {
    if (this.openRequested) {
      this.openRequested = false;
      openRenderer();
    }

    advanceClock(deltaMs);
    reportLoads();

    final VideoFrame frame = this.pipeline.latestFrame();

    if (frame == null) {
      // Nothing rendered yet, or nothing ever: a context still opening, one that failed to open
      // on a platform without an implementation, or a shader whose first read never compiled.
      // Background says what shows through.
      setColors(ProjectionParams.backgroundColor(this.projection.backgroundMode.getEnum()));
      return;
    }

    this.projection.project(frame, this.model, this.colors);
  }

  private void advanceClock(double deltaMs) {
    if (this.play.isOn()) {
      this.elapsedMs += deltaMs * this.speed.getValue();
    }

    final ShaderSource rendering = this.source;

    if (rendering != null) {
      rendering.setTimeSeconds(this.elapsedMs / MILLISECONDS_PER_SECOND);
    }
  }

  /**
   * Pick up whatever the render thread made of the last read.
   *
   * Polled rather than pushed because the read happens on the other thread, and often without
   * anyone asking: a file being watched reloads itself the moment it is saved.
   */
  private void reportLoads() {
    final ShaderSource rendering = this.source;

    if (rendering == null || rendering.loadCount() == this.reportedLoadCount) {
      return;
    }

    this.reportedLoadCount = rendering.loadCount();

    final String failure = rendering.compileError();

    this.error.setValue((failure == null) ? "" : failure);

    if (failure != null) {
      LX.log(String.format("[LaserphileShader] %s%n%s", describeShader(), failure));
    }
  }

  private String describeShader() {
    final String raw = this.fileName.getString();

    return (raw == null || raw.isBlank()) ? "built-in shader" : raw;
  }
}
