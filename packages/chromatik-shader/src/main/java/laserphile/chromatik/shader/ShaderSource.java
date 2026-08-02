package laserphile.chromatik.shader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import laserphile.chromatik.core.FrameSource;
import laserphile.chromatik.core.VideoFrame;

/**
 * A frame source whose frames are rendered rather than decoded.
 *
 * It is a live source: there is no timeline, nothing to seek, and the only frame worth keeping is
 * the newest, so the pipeline buffers it in a single slot exactly as it does a screen capture.
 *
 * The OpenGL context is created inside {@link #open} and used only from there on, which is what
 * makes the threading work. A context belongs to one thread, and the frame pipeline gives this
 * class a thread of its own for the whole of its life: open, every grab, and close all happen on
 * it. The engine thread never touches GL. What has to cross does so through volatile fields: the
 * shader's sense of time on the way in, and the compile result on the way back.
 */
final class ShaderSource implements FrameSource {

  private static final long NANOSECONDS_PER_MS = 1_000_000L;
  private static final double MILLISECONDS_PER_SECOND = 1000;

  /** Sane pace when the engine reports nothing useful, matching Chromatik's own default. */
  private static final double FALLBACK_FRAMES_PER_SECOND = 60;

  /**
   * How often the shader file is asked whether it has changed, and equally the settling time an
   * edit has to survive before it is read. Editors do not write a file atomically, so a save can
   * be observed halfway done; requiring the size and timestamp to hold still across two looks is
   * what keeps a half-written file from being reported as a syntax error.
   */
  private static final long QUARTER_SECOND_IN_NANOS = 250 * NANOSECONDS_PER_MS;

  /** Renders until a file is chosen, and proves the context works before any file handling does. */
  private static final String BUILT_IN_SHADER = """
    uniform float time;
    uniform vec2 resolution;

    void main() {
      vec2 uv = gl_FragCoord.xy / resolution;
      float wave = 0.5 + 0.5 * sin((uv.x * 6.0) + time);
      float ring = 0.5 + 0.5 * sin((length(uv - 0.5) * 18.0) - (time * 2.0));
      gl_FragColor = vec4(wave, ring, 1.0 - (wave * ring), 1.0);
    }
    """;

  /** Null renders the built-in shader. */
  private final File shaderFile;

  private final double targetFrameRate;

  /** Written by the engine thread each render, read by the GL thread each grab. */
  private volatile double timeSeconds = 0;

  /**
   * The shader's own uniform values, already scaled out of knob positions into the units the
   * shader expects, in declaration order.
   *
   * Replaced wholesale rather than written into, so the render thread always reads an array that
   * is internally consistent instead of one caught mid-update.
   */
  private volatile float[] uniformValues = new float[0];

  /** Written by the GL thread on every load, read by the engine thread to drive the panel. */
  private volatile String compileError = null;
  private volatile List<UniformDeclaration> declarations = List.of();
  private volatile int loadCount = 0;

  private OffscreenContext context;
  private ShaderRenderer renderer;
  private int edge;
  private int frameNumber = 0;
  private long nextFrameDueNanos;

  // What was on disk when the shader was last read, and what the most recent look saw, so a change
  // has to be observed twice before it counts.
  private long loadedModifiedMs = -1;
  private long loadedLength = -1;
  private long observedModifiedMs = -1;
  private long observedLength = -1;
  private long nextEditCheckNanos;

  ShaderSource(File shaderFile, double engineFrameRate) {
    this.shaderFile = shaderFile;
    this.targetFrameRate = (engineFrameRate > 0) ? engineFrameRate : FALLBACK_FRAMES_PER_SECOND;
  }

  /** Where the engine hands the shader its clock. Engine thread. */
  void setTimeSeconds(double timeSeconds) {
    this.timeSeconds = timeSeconds;
  }

  /**
   * Where the engine hands over the knob values, in the units the shader declared rather than as
   * knob positions. Engine thread. The array is taken as given and never written to afterwards.
   */
  void setUniformValues(float[] uniformValues) {
    this.uniformValues = uniformValues;
  }

  /** The last compile's failure text, or null if the current program is good. Engine thread. */
  String compileError() {
    return this.compileError;
  }

  /** The uniforms the loaded shader declares, in declaration order. Engine thread. */
  List<UniformDeclaration> declarations() {
    return this.declarations;
  }

  /**
   * Bumped every time the shader is read, whether or not it compiled. The engine watches this to
   * notice a reload it did not ask for, which is what a hot reload is.
   */
  int loadCount() {
    return this.loadCount;
  }

  @Override
  public void open(int longestEdge) throws Exception {
    this.edge = longestEdge;

    // Only a genuinely broken environment throws here, and the pipeline logging it is right. A
    // shader that will not compile is a different matter and must not take the thread down.
    this.context = OffscreenContext.forThisPlatform();
    this.context.makeCurrent();

    this.renderer = new ShaderRenderer(this.edge);
    this.renderer.initialize();

    loadShader();

    this.nextFrameDueNanos = System.nanoTime();
    this.nextEditCheckNanos = System.nanoTime() + QUARTER_SECOND_IN_NANOS;
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
    // The pipeline's live loop calls this as fast as it returns, so the pace has to come from
    // here. Rendering faster than the engine consumes would spend GPU on frames nobody sees.
    awaitNextFrame();
    reloadIfEdited();

    if (!this.renderer.hasProgram()) {
      return null; // nothing compiled yet, or the very first read failed
    }

    final double atSeconds = this.timeSeconds;
    this.frameNumber++;

    return this.renderer.render(atSeconds, this.frameNumber,
      (long) (atSeconds * MILLISECONDS_PER_SECOND), this.uniformValues);
  }

  private void awaitNextFrame() throws InterruptedException {
    final long frameIntervalNanos =
      (long) (NANOSECONDS_PER_MS * MILLISECONDS_PER_SECOND / this.targetFrameRate);
    final long waitNanos = this.nextFrameDueNanos - System.nanoTime();

    if (waitNanos > 0) {
      Thread.sleep(waitNanos / NANOSECONDS_PER_MS, (int) (waitNanos % NANOSECONDS_PER_MS));
    } else {
      // Fell behind, so give up on catching up rather than sprinting through a backlog of frames
      // whose moment has passed.
      this.nextFrameDueNanos = System.nanoTime();
    }

    this.nextFrameDueNanos += frameIntervalNanos;
  }

  /**
   * Read the file again if it has changed and then held still, so that saving in an editor is all
   * it takes to see the change.
   */
  private void reloadIfEdited() {
    if (this.shaderFile == null || System.nanoTime() < this.nextEditCheckNanos) {
      return;
    }

    this.nextEditCheckNanos = System.nanoTime() + QUARTER_SECOND_IN_NANOS;

    final long modifiedMs = this.shaderFile.lastModified();
    final long length = this.shaderFile.length();

    final boolean settled =
      (modifiedMs == this.observedModifiedMs) && (length == this.observedLength);
    final boolean changed =
      (modifiedMs != this.loadedModifiedMs) || (length != this.loadedLength);

    this.observedModifiedMs = modifiedMs;
    this.observedLength = length;

    if (settled && changed) {
      loadShader();
    }
  }

  /**
   * Read, prepare and compile the shader, replacing what is running only if all three work.
   *
   * Never throws. Every way this can fail is something the user can fix from their editor, so each
   * one becomes text on the device rather than an exception that would take the render thread and
   * the context down with it.
   */
  private void loadShader() {
    this.loadedModifiedMs = (this.shaderFile == null) ? -1 : this.shaderFile.lastModified();
    this.loadedLength = (this.shaderFile == null) ? -1 : this.shaderFile.length();

    final String userSource;

    if (this.shaderFile == null) {
      userSource = BUILT_IN_SHADER;
    } else {
      try {
        userSource = Files.readString(this.shaderFile.toPath(), StandardCharsets.UTF_8);
      } catch (IOException unreadable) {
        fail(String.format("cannot read %s: %s", this.shaderFile.getName(),
          unreadable.getMessage()));
        return;
      }
    }

    final String prepared;

    try {
      prepared = ShaderText.prepare(userSource);
    } catch (ShaderText.UnusableShaderException unusable) {
      fail(unusable.getMessage());
      return;
    }

    final List<UniformDeclaration> parsed = UniformParser.controls(userSource);
    final String compileLog = this.renderer.compile(prepared, parsed);

    if (compileLog != null) {
      fail(compileLog.strip());
      return;
    }

    // Start every uniform at the value its author asked for, so a freshly loaded shader looks the
    // way they meant it to before anything is touched. Without this they would all sit at zero,
    // which for several of these shaders is a divide by zero or a flat frame.
    final float[] defaults = new float[parsed.size()];
    for (int index = 0; index < parsed.size(); index++) {
      defaults[index] = (float) parsed.get(index).defaultValue();
    }

    this.uniformValues = defaults;
    this.declarations = parsed;
    this.compileError = null;

    published();
  }

  /**
   * Keep whatever was already running and record why the new source did not replace it. A typo
   * mid-show should cost the edit, not the output.
   */
  private void fail(String message) {
    this.compileError = message;

    published();
  }

  /**
   * Announce that a load has finished, and always last.
   *
   * The engine watches this count and reads everything else the moment it moves, so the count has
   * to be written after the things it is announcing rather than before. Bumping it first leaves a
   * window in which the engine sees a new load and reads the previous load's uniforms, which it
   * then keeps, because from its point of view that load has already been dealt with. Writing a
   * volatile last is also what publishes the ordinary fields written above it.
   */
  private void published() {
    this.loadCount++;
  }

  @Override
  public void seek(long mediaTimeMs) {
    // No timeline to seek along. The engine drives time directly through setTimeSeconds.
  }

  @Override
  public void close() {
    if (this.renderer != null) {
      this.renderer.dispose();
      this.renderer = null;
    }

    if (this.context != null) {
      this.context.close();
      this.context = null;
    }
  }

  @Override
  public String toString() {
    final String name = (this.shaderFile == null) ? "built-in" : this.shaderFile.getName();

    return String.format("shader %s at %dx%d", name, this.edge, this.edge);
  }
}
