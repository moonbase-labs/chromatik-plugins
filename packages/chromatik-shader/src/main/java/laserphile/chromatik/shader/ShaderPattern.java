package laserphile.chromatik.shader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import laserphile.chromatik.core.FramePipeline;
import laserphile.chromatik.core.ProjectionControls;
import laserphile.chromatik.core.ProjectionParams;
import laserphile.chromatik.core.VideoFrame;
import laserphile.chromatik.core.WorkingResolution;

import heronarts.glx.GLX;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIKnob;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.component.UISwitch;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.LXSerializable;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXListenableNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.parameter.MutableParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;

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
public class ShaderPattern extends LXPattern implements UIDeviceControls<ShaderPattern> {

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
   * This package's folder under ~/Chromatik, which Chromatik creates from the mediaDir declared in
   * lx.package. Where the demo shaders are staged and where Browse opens by default.
   */
  private static final String SHADER_MEDIA_FOLDER = "LaserphileShader";

  /**
   * Saved under a prefix so that a shader is free to call a uniform whatever it likes without
   * colliding with a control this pattern already owns. A shader with a uniform called "level"
   * would otherwise fail to register it, and silently lose the knob.
   */
  private static final String UNIFORM_KEY_PREFIX = "uniform-";

  /**
   * How many of the shader's own knobs come before Speed and Level.
   *
   * A control surface binds only its first eight, so this is the split: the shader's uniforms are
   * the interesting thing to play and go first, but Speed and Level are worth a knob on every
   * pattern and should not be pushed off the surface by a shader that declares a lot. Anything
   * past this still appears on the panel and can still be mapped by hand.
   */
  private static final int UNIFORM_KNOBS_BEFORE_TRANSPORT = 6;

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

  /**
   * Bumped whenever the shader's controls have been rebuilt, so a device panel knows to draw a
   * different set of knobs. Not registered as a parameter: it carries a signal rather than a
   * value, and there would be nothing useful to save.
   */
  public final MutableParameter onReload = new MutableParameter("Reload");

  /** Orientation, scale, wrapping, sampling and brightness, shared with the other patterns. */
  public final ProjectionControls projection = new ProjectionControls();

  private final FramePipeline pipeline = new FramePipeline();

  /**
   * The shader's own uniforms and the controls driving them, in declaration order.
   *
   * Replaced wholesale rather than mutated, because a device panel reads it from the UI thread
   * while a reload rebuilds it on the engine thread, and a half-rebuilt list is not something to
   * draw.
   */
  private volatile List<UniformControl> uniformControls = List.of();

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

    updateRemoteControls();

    this.fileName.addListener(parameter -> this.openRequested = true);
    this.workingResolution.addListener(parameter -> this.openRequested = true);
    this.browse.onTrigger(this::showFileChooser);
    this.reload.onTrigger(() -> this.openRequested = true);
  }

  /**
   * Put the shader's own knobs in front of everything else on a control surface.
   *
   * Rebuilt rather than set once, because how many knobs there are depends on the shader that
   * happens to be loaded, and that changes whenever a file is chosen or saved.
   */
  private void updateRemoteControls() {
    final List<UniformControl> controls = this.uniformControls;
    final List<LXListenableNormalizedParameter> remote = new ArrayList<>();

    // Continuous first and only up to the split, so Speed and Level always land on a knob rather
    // than being pushed past the eighth by a shader with a lot to say.
    final List<LXListenableNormalizedParameter> continuous = new ArrayList<>();
    final List<LXListenableNormalizedParameter> switches = new ArrayList<>();

    for (UniformControl control : controls) {
      if (control.parameter() instanceof BooleanParameter) {
        switches.add(control.parameter());
      } else {
        continuous.add(control.parameter());
      }
    }

    final int leading = Math.min(continuous.size(), UNIFORM_KNOBS_BEFORE_TRANSPORT);

    remote.addAll(continuous.subList(0, leading));
    remote.add(this.speed);
    remote.add(this.projection.level);
    remote.addAll(continuous.subList(leading, continuous.size()));

    // A switch costs a knob outright on a surface that cannot page past its eighth, so every one
    // of them goes after the continuous controls no matter what order they were declared in.
    remote.addAll(switches);
    remote.add(this.play);

    // Read out of the shared collections rather than listed again here, so that a change to which
    // projection control deserves a knob is made once, in the core package, and every pattern
    // follows. Listing them by hand drifts silently the moment that ordering is revised.
    appendMappable(remote, this.projection.knobParameters);
    appendMappable(remote, this.projection.remainingParameters);

    // Browse, Reload and Res stay out of the list. The first two read a file from disk, which is
    // not something to hand to a control surface, and Res tears the context down and builds
    // another.
    setCustomRemoteControls(remote.toArray(new LXListenableNormalizedParameter[0]));
  }

  /**
   * Add every control in a collection that a surface could actually drive, in its declared order.
   *
   * Everything in these two happens to be normalised today. The check is here because that is a
   * property of the core package rather than of this one, and a control added there that is not
   * would otherwise be a class cast at construction.
   */
  private static void appendMappable(List<LXListenableNormalizedParameter> remote,
      LXParameter.Collection collection) {
    for (LXParameter parameter : collection.values()) {
      if (parameter instanceof LXListenableNormalizedParameter mappable) {
        remote.add(mappable);
      }
    }
  }

  /**
   * Throw away the previous shader's controls and build the new one's.
   *
   * Engine thread only. Registering under the declared name is what makes a saved project able to
   * find its way back to the right knob after the shader has been edited, and what makes a knob
   * quietly disappear if the uniform it drove was renamed. That is the honest outcome: the value
   * belonged to a uniform that no longer exists.
   */
  private void rebuildUniformControls(List<UniformDeclaration> declarations) {
    if (alreadyBuiltFor(declarations)) {
      return;
    }

    for (UniformControl existing : this.uniformControls) {
      // Unregistered but deliberately not disposed. Disposing clears a parameter's listener list,
      // and the panels drawing these knobs have listeners on them: Chromatik's performance device
      // rebuilds itself the moment the remote controls change below, and the first thing it does
      // is dispose its old controls, each of which then tries to remove a listener from a
      // parameter that no longer has a list to remove it from. That throws, once per knob, out of
      // run() and onto the engine thread. Leaving the parameter intact lets every panel let go of
      // it in its own time, after which nothing refers to it.
      removeParameter(existing.parameter(), false);
    }

    final List<UniformControl> rebuilt = new ArrayList<>();

    for (UniformDeclaration declaration : declarations) {
      final LXListenableNormalizedParameter parameter = controlFor(declaration);

      addParameter(UNIFORM_KEY_PREFIX + declaration.name(), parameter);
      rebuilt.add(new UniformControl(declaration, parameter));
    }

    this.uniformControls = List.copyOf(rebuilt);

    updateRemoteControls();

    // Tells any open device panel that the knobs it drew are no longer the right ones.
    this.onReload.setValue(this.onReload.getValue() + 1);
  }

  /**
   * Whether the controls standing now are already the ones these declarations describe.
   *
   * Opening a project asks for the controls twice: once from {@link #load}, which reads the file
   * off disk so the saved values have somewhere to land, and again when the render thread reports
   * the same file compiled. Tearing perfectly good controls down and building identical ones costs
   * every knob a panel has drawn on them, and the panels do not all survive having a parameter
   * swapped underneath them mid-rebuild. Nothing changed, so nothing is rebuilt.
   *
   * Compared by value rather than by name alone, so that editing a @range in the file still counts
   * as a change and still moves the knob's scale.
   */
  private boolean alreadyBuiltFor(List<UniformDeclaration> declarations) {
    final List<UniformControl> standing = this.uniformControls;

    if (standing.size() != declarations.size()) {
      return false;
    }

    for (int index = 0; index < declarations.size(); index++) {
      if (!standing.get(index).declaration().equals(declarations.get(index))) {
        return false;
      }
    }

    return true;
  }

  private static LXListenableNormalizedParameter controlFor(UniformDeclaration declaration) {
    if (declaration.type().equals("bool")) {
      return new BooleanParameter(declaration.name(), declaration.defaultValue() >= 0.5)
        .setDescription(String.format("Shader uniform: bool %s", declaration.name()));
    }

    // The knob holds a 0 to 1 position and the declared range is applied on the way to the shader,
    // so a project keeps working when someone widens the range in the file.
    return new CompoundParameter(declaration.name(), declaration.defaultPosition())
      .setDescription(String.format("Shader uniform: %s %s, %s to %s",
        declaration.type(), declaration.name(),
        trimmed(declaration.minimum()), trimmed(declaration.maximum())));
  }

  private static String trimmed(double value) {
    return (value == Math.rint(value)) ? String.valueOf((long) value) : String.valueOf(value);
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
   * browse landed in, else this package's own shader folder.
   *
   * That last one is the point of declaring a mediaDir. Chromatik creates ~/Chromatik/{@value
   * #SHADER_MEDIA_FOLDER} for this package on startup, and it is where the shipped demo shaders
   * are meant to live, so opening there puts something to pick in front of someone who has never
   * used the pattern before. Falling back to the media root instead would open on a folder of
   * other plugins' folders.
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

    final File shaderFolder = this.lx.getMediaFile(SHADER_MEDIA_FOLDER);

    if (shaderFolder != null && shaderFolder.isDirectory()) {
      return asFolderPath(shaderFolder.getAbsolutePath());
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
    pushUniformValues();

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
      return;
    }

    // Only a shader that compiled gets to change the controls. A failed edit leaves the previous
    // one running, so its knobs are still the ones that mean anything.
    rebuildUniformControls(rendering.declarations());
  }

  /** Hand the render thread the shader's uniform values, in the units it declared them in. */
  private void pushUniformValues() {
    final ShaderSource rendering = this.source;
    final List<UniformControl> controls = this.uniformControls;

    if (rendering == null) {
      return;
    }

    final float[] values = new float[controls.size()];

    for (int index = 0; index < controls.size(); index++) {
      values[index] = controls.get(index).value();
    }

    rendering.setUniformValues(values);
  }

  /**
   * Build the shader's controls before the saved values land on them.
   *
   * A pattern's parameters normally all exist by the time anything is loaded into them, but these
   * ones depend on a file whose path is itself in the project being loaded. Left alone, every
   * uniform value in a saved project would be dropped for want of a parameter to go on. Reading
   * the shader here is only text work, so it needs no OpenGL and no render thread, which is what
   * makes it possible this early.
   */
  @Override
  public void load(LX lx, JsonObject object) {
    final JsonElement savedFile = LXSerializable.Utils.getParameter(object, "file");

    if (savedFile != null && savedFile.isJsonPrimitive()) {
      this.fileName.setValue(savedFile.getAsString());
      rebuildUniformControls(declarationsOnDisk());
    }

    super.load(lx, object);

    // Again, and it has to be after. A device serialises its remote control list, so super.load
    // restores whatever was saved, and what was saved is the set from before this shader's knobs
    // existed. Building them first is what gives the saved values somewhere to land; putting them
    // on a surface has to wait until the restore has had its turn.
    updateRemoteControls();
  }

  /**
   * What the chosen file declares, read straight off disk. Text only, so this is safe anywhere.
   *
   * An unreadable or missing file gives no controls rather than an error: the path is reported
   * elsewhere, and a project opened on a machine without the shader should still open.
   */
  private List<UniformDeclaration> declarationsOnDisk() {
    final File file = chosenFile();

    if (file == null) {
      return List.of();
    }

    try {
      return UniformParser.controls(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    } catch (IOException unreadable) {
      return List.of();
    }
  }

  private String describeShader() {
    final String raw = this.fileName.getString();

    return (raw == null || raw.isBlank()) ? "built-in shader" : raw;
  }

  private static final float PANEL_COLUMN_WIDTH = 76;
  private static final float UNIFORM_SECTION_WIDTH = 200;
  private static final float ERROR_LABEL_HEIGHT = 46;

  /**
   * Draw the device panel.
   *
   * A custom panel rather than the default one for two reasons, both of which the default cannot
   * do. It builds its controls by walking the parameter list exactly once and never listens for
   * more, so a uniform knob created when a shader is loaded would never appear. And it draws only
   * numbers, switches and dropdowns, so the compiler's complaint about a shader that will not
   * build would have nowhere to go but the log.
   *
   * Chromatik resolves a device's controls by checking whether the component is itself a
   * {@link UIDeviceControls} before it consults the plugin registry, which is why this lives on
   * the pattern. The registry route needs an {@code LXPlugin}, and that is gated behind a licence
   * tier this package does not require.
   */
  @Override
  public void buildDeviceControls(LXStudio.UI ui, UIDevice device, ShaderPattern pattern) {
    device.setLayout(UI2dContainer.Layout.HORIZONTAL, 4);
    device.setChildSpacing(6);

    addColumn(device, "File",
      newButton(pattern.browse, PANEL_COLUMN_WIDTH),
      newButton(pattern.reload, PANEL_COLUMN_WIDTH),
      newDropMenu(pattern.workingResolution, PANEL_COLUMN_WIDTH));

    addColumn(device, "Shader",
      newKnob(pattern.speed),
      newKnob(pattern.projection.level));

    addColumn(device, "Time", newButton(pattern.play, PANEL_COLUMN_WIDTH));

    // Rebuilt from scratch whenever a shader loads, because which knobs belong here is a property
    // of the file rather than of the pattern.
    final UI2dContainer uniformSection =
      UI2dContainer.newHorizontalContainer(UIKnob.HEIGHT, 4)
        .setChildSpacing(4);

    final UILabel errorLabel = (UILabel) new UILabel(0, 0, UNIFORM_SECTION_WIDTH,
      ERROR_LABEL_HEIGHT)
      .setBreakLines(true)
      .setVisible(false);

    addColumn(device, UNIFORM_SECTION_WIDTH, "Uniforms", uniformSection, errorLabel);

    final LXParameterListener redraw = parameter -> {
      uniformSection.removeAllChildren();

      for (UniformControl control : pattern.uniformControls) {
        if (control.parameter() instanceof BooleanParameter switched) {
          new UISwitch(0, 0, switched).addToContainer(uniformSection);
        } else {
          new UIKnob(0, 0, control.parameter()).addToContainer(uniformSection);
        }
      }

      final String failure = pattern.error.getString();
      final boolean failed = (failure != null) && !failure.isBlank();

      errorLabel.setLabel(failed ? failure : "");
      errorLabel.setVisible(failed);

      device.redraw();
    };

    // Two signals, because the two things this panel shows change independently: the knobs when a
    // shader loads, and the message when one fails to.
    device.addListener(pattern.onReload, redraw);
    device.addListener(pattern.error, redraw);

    redraw.onParameterChanged(pattern.onReload);
  }
}
