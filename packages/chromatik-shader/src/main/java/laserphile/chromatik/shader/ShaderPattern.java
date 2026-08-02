package laserphile.chromatik.shader;

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
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
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

  /**
   * What renders until a shader file is chosen, which is also what proves the context, the
   * framebuffer and the readback are all working before any of the file handling is involved.
   */
  private static final String BUILT_IN_SHADER = """
    #version 330 core

    uniform float time;
    uniform vec2 resolution;

    out vec4 fragColor;

    void main() {
      vec2 uv = gl_FragCoord.xy / resolution;
      float wave = 0.5 + 0.5 * sin((uv.x * 6.0) + time);
      float ring = 0.5 + 0.5 * sin((length(uv - 0.5) * 18.0) - (time * 2.0));
      fragColor = vec4(wave, ring, 1.0 - (wave * ring), 1.0);
    }
    """;

  /** Orientation, scale, wrapping, sampling and brightness, shared with the other patterns. */
  public final ProjectionControls projection = new ProjectionControls();

  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0, 4).setExponent(2)
      .setDescription("How fast the shader's clock advances: 1 is real time");
  public final BooleanParameter play =
    new BooleanParameter("Play", true).setDescription("Advance the shader's clock");

  public final DiscreteParameter workingResolution =
    new DiscreteParameter("Res", WorkingResolution.OPTIONS, WorkingResolution.AUTO_OPTION)
      .setDescription("Edge length to render at; Auto follows the model's point count");

  private final FramePipeline pipeline = new FramePipeline();

  /** Held so the engine can push the clock at it. Null whenever nothing is open. */
  private ShaderSource source = null;

  /**
   * The shader's own clock, in milliseconds, advanced by run() rather than read off the wall.
   * Driving it here rather than in the renderer is what lets Speed scale it and Play hold it
   * without the render thread knowing either control exists.
   */
  private double elapsedMs = 0;

  // Parameter listeners can fire on the UI thread, so they only raise a flag; run() acts on it.
  private volatile boolean openRequested = false;

  public ShaderPattern(LX lx) {
    super(lx);

    // The panel is drawn in this order, and it matches the remote control order below so that the
    // eight knobs of a control surface line up with the panel's top row.
    addParameter("level", this.projection.level);
    addParameter("speed", this.speed);
    // Speed has taken a knob, so the last of these lands one past the knob row.
    addParameters(this.projection.knobParameters);

    addParameter("play", this.play);
    addParameters(this.projection.remainingParameters);

    // Held back from the surface, so last. Anything surface-less registered earlier would offset
    // every control after it and break the panel's match with the knobs.
    addParameter("workingResolution", this.workingResolution);

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
    // Res stays out of the list. It is read when the renderer opens, so changing it tears the
    // context down and builds another, which is not something to hand to a knob.

    this.workingResolution.addListener(parameter -> this.openRequested = true);
  }

  private void openRenderer() {
    this.pipeline.stop();
    this.source = null;

    // The engine's frame rate is the render rate: there is no point drawing faster than the
    // renderer consumes, and every extra frame is a full readback off the GPU. Read here rather
    // than watched, because reopening per drag increment would stall rendering for the drag.
    final double engineFrameRate = this.lx.engine.framesPerSecond.getValue();
    final ShaderSource opening = new ShaderSource(BUILT_IN_SHADER, engineFrameRate);

    this.source = opening;
    this.pipeline.start(
      opening, WorkingResolution.edgeFor(this.workingResolution.getValuei(), this.model.size));
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

    final VideoFrame frame = this.pipeline.latestFrame();

    if (frame == null) {
      // Nothing rendered yet, or nothing ever: a context still opening, or one that failed to
      // open on a platform without an implementation. Background says what shows through.
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
}
