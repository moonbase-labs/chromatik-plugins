package laserphile.chromatik.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLX;
import org.lwjgl.opengl.GLX13;
import org.lwjgl.opengl.GLXARBCreateContext;
import org.lwjgl.opengl.GLXARBCreateContextProfile;
import org.lwjgl.opengl.GLXCapabilities;
import org.lwjgl.system.MemoryStack;

/**
 * A Linux offscreen context, from GLX onto a one-pixel pbuffer.
 *
 * GLX will not make a context current without a drawable, so there has to be one, but nothing ever
 * looks at it: everything is rendered into a framebuffer object instead. One pixel is the smallest
 * thing that satisfies the rule.
 *
 * The X display comes from GLFW rather than from opening a second connection of our own. Chromatik
 * has already initialised GLFW and opened a display by the time any of this runs, and sharing it
 * avoids both a redundant connection and any question of which one a driver associates a context
 * with. The cost is that this only works under X11: on Wayland GLFW has no X display to hand over,
 * which is reported rather than guessed at.
 *
 * Not verified on hardware. Unlike the macOS path, which was built against a machine that runs it,
 * this is written from the GLX specification and compiles against the real bindings but has never
 * had a Linux GPU under it.
 */
final class LinuxContext implements OffscreenContext {

  /** Nothing is ever drawn into the drawable itself, so it only has to exist. */
  private static final int PBUFFER_EDGE = 1;

  private final long display;

  private long pbuffer;
  private long context;

  LinuxContext() {
    this.display = GLFWNativeX11.glfwGetX11Display();

    if (this.display == 0L) {
      throw new IllegalStateException(
        "no X11 display available. The Shader pattern gets one from GLFW, which Chromatik has "
          + "already opened, so this usually means the session is Wayland rather than X11.");
    }

    // Screen 0 is the one GLFW opened the display against, and a second screen on a single
    // connection is rare enough that guessing right matters more than covering it.
    final int screen = 0;
    final GLXCapabilities glx = GL.createCapabilitiesGLX(this.display, screen);

    if (!glx.GLX13) {
      throw new IllegalStateException("GLX 1.3 is required for an offscreen pbuffer");
    }
    if (!glx.GLX_ARB_create_context || !glx.GLX_ARB_create_context_profile) {
      throw new IllegalStateException(
        "GLX_ARB_create_context_profile is required to ask for a 3.3 core profile");
    }

    try (MemoryStack stack = MemoryStack.stackPush()) {
      // Only what actually matters: something that can back a pbuffer and can do RGBA. Asking for
      // specific channel depths as well would rule out perfectly good configurations.
      final PointerBuffer configs = GLX13.glXChooseFBConfig(this.display, screen, stack.ints(
        GLX13.GLX_DRAWABLE_TYPE, GLX13.GLX_PBUFFER_BIT,
        GLX13.GLX_RENDER_TYPE, GLX13.GLX_RGBA_BIT,
        0));

      if (configs == null || configs.limit() == 0) {
        throw new IllegalStateException("no GLX framebuffer configuration supports a pbuffer");
      }

      final long config = configs.get(0);

      this.pbuffer = GLX13.glXCreatePbuffer(this.display, config, stack.ints(
        GLX13.GLX_PBUFFER_WIDTH, PBUFFER_EDGE,
        GLX13.GLX_PBUFFER_HEIGHT, PBUFFER_EDGE,
        0));

      if (this.pbuffer == 0L) {
        throw new IllegalStateException("glXCreatePbuffer failed");
      }

      this.context = GLXARBCreateContext.glXCreateContextAttribsARB(
        this.display, config, 0L, true, stack.ints(
          GLXARBCreateContext.GLX_CONTEXT_MAJOR_VERSION_ARB, 3,
          GLXARBCreateContext.GLX_CONTEXT_MINOR_VERSION_ARB, 3,
          GLXARBCreateContextProfile.GLX_CONTEXT_PROFILE_MASK_ARB,
          GLXARBCreateContextProfile.GLX_CONTEXT_CORE_PROFILE_BIT_ARB,
          0));

      if (this.context == 0L) {
        GLX13.glXDestroyPbuffer(this.display, this.pbuffer);
        this.pbuffer = 0L;

        throw new IllegalStateException("glXCreateContextAttribsARB failed for a 3.3 core profile");
      }
    }
  }

  @Override
  public void makeCurrent() {
    if (!GLX13.glXMakeContextCurrent(this.display, this.pbuffer, this.pbuffer, this.context)) {
      throw new IllegalStateException("glXMakeContextCurrent failed");
    }

    GL.createCapabilities();
  }

  @Override
  public void close() {
    if (this.context != 0L) {
      GLX13.glXMakeContextCurrent(this.display, 0L, 0L, 0L);
      GLX.glXDestroyContext(this.display, this.context);
      this.context = 0L;
    }

    if (this.pbuffer != 0L) {
      GLX13.glXDestroyPbuffer(this.display, this.pbuffer);
      this.pbuffer = 0L;
    }

    // The display belongs to GLFW, so it is never closed here.
  }
}
