package laserphile.chromatik.shader;

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
 * it. The engine thread never touches GL. The one thing that has to cross is the shader's sense of
 * time, and that goes through a volatile field rather than a lock, since a frame rendered against
 * a reading a millisecond stale is not a frame anyone can see is wrong.
 */
final class ShaderSource implements FrameSource {

  private static final long NANOSECONDS_PER_MS = 1_000_000L;
  private static final double MILLISECONDS_PER_SECOND = 1000;

  /** Sane pace when the engine reports nothing useful, matching Chromatik's own default. */
  private static final double FALLBACK_FRAMES_PER_SECOND = 60;

  private final String fragmentSource;
  private final double targetFrameRate;

  /** Written by the engine thread each render, read by the GL thread each grab. */
  private volatile double timeSeconds = 0;

  private OffscreenContext context;
  private ShaderRenderer renderer;
  private int edge;
  private long nextFrameDueNanos;

  ShaderSource(String fragmentSource, double engineFrameRate) {
    this.fragmentSource = fragmentSource;
    this.targetFrameRate =
      (engineFrameRate > 0) ? engineFrameRate : FALLBACK_FRAMES_PER_SECOND;
  }

  /** Where the engine hands the shader its clock. Engine thread. */
  void setTimeSeconds(double timeSeconds) {
    this.timeSeconds = timeSeconds;
  }

  @Override
  public void open(int longestEdge) throws Exception {
    this.edge = longestEdge;

    this.context = OffscreenContext.forThisPlatform();
    this.context.makeCurrent();

    this.renderer = new ShaderRenderer(this.edge);
    this.renderer.initialize();

    final String compileLog = this.renderer.compile(this.fragmentSource);
    if (compileLog != null) {
      throw new IllegalStateException(String.format("shader failed to compile: %s", compileLog));
    }

    this.nextFrameDueNanos = System.nanoTime();
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

    if (!this.renderer.hasProgram()) {
      return null;
    }

    final double atSeconds = this.timeSeconds;

    return this.renderer.render(atSeconds, (long) (atSeconds * MILLISECONDS_PER_SECOND));
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
    return String.format("shader %dx%d", this.edge, this.edge);
  }
}
