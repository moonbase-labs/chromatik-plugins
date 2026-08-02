package laserphile.chromatik.shader;

import org.lwjgl.system.Platform;

/**
 * An OpenGL context with no window behind it, for rendering into a framebuffer nobody displays.
 *
 * Chromatik offers nothing to borrow here. Its renderer is bgfx rather than OpenGL, and the one
 * window it does own is created with {@code GLFW_CLIENT_API = GLFW_NO_API}, so there is no GL
 * context on it to make current. A shader pattern has to bring its own.
 *
 * Every implementation goes straight to the platform's own context API instead of asking GLFW for
 * a hidden window. That is not a stylistic preference: Chromatik launches with
 * {@code -XstartOnFirstThread} so that GLFW can own the main thread, and GLFW may only create a
 * window from that thread. The rendering happens on a frame pipeline's thread, which is never it.
 * The platform APIs have no such rule, so this sidesteps the problem everywhere rather than only
 * on the platforms that happen to tolerate the breach.
 *
 * A context belongs to whichever thread made it current, so one of these is created, used and
 * closed entirely on a single thread.
 */
interface OffscreenContext extends AutoCloseable {

  /** Bind this context to the calling thread. Call once, on the thread that will render. */
  void makeCurrent();

  @Override
  void close();

  /**
   * The context implementation for the machine this is running on.
   *
   * @throws UnsupportedOperationException on a platform with no implementation, which is the
   *     honest answer: the pattern reports it and renders its background rather than failing
   *     somewhere deeper with a native stack trace.
   */
  static OffscreenContext forThisPlatform() {
    return switch (Platform.get()) {
      case MACOSX -> new MacosContext();
      case WINDOWS -> throw unsupported("Windows");
      case LINUX -> new LinuxContext();
      case FREEBSD -> throw unsupported("FreeBSD");
    };
  }

  private static UnsupportedOperationException unsupported(String platformName) {
    return new UnsupportedOperationException(String.format(
      "the Shader pattern cannot create an OpenGL context on %s yet", platformName));
  }
}
