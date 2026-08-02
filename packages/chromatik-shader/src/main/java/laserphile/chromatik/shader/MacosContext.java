package laserphile.chromatik.shader;

import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

/**
 * A macOS offscreen context, straight from CGL.
 *
 * CGL is the layer underneath NSOpenGLContext, and a context made this way has no drawable
 * attached: there is no window, no view, and nothing for AppKit to want the main thread for. That
 * is the whole reason this exists rather than a hidden GLFW window, which macOS would insist be
 * created on the thread Chromatik has already given to GLFW.
 *
 * The profile asked for is 3.2 Core, which is the only core profile macOS offers and which it
 * satisfies with 4.1. That is comfortably above the {@code #version 330} the shader prologue
 * injects.
 */
final class MacosContext implements OffscreenContext {

  /**
   * Lets the context land on a GPU that is not driving a display, which matters on a Mac with
   * switchable graphics and costs nothing on one without.
   */
  private static final int OFFLINE_RENDERERS_ALLOWED = CGL.kCGLPFAAllowOfflineRenderers;

  private long pixelFormat;
  private long context;

  MacosContext() {
    try (MemoryStack stack = MemoryStack.stackPush()) {
      final IntBuffer attributes = stack.ints(
        CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_3_2_Core,
        CGL.kCGLPFAAccelerated,
        OFFLINE_RENDERERS_ALLOWED,
        CGL.kCGLPFAColorSize, 24,
        CGL.kCGLPFAAlphaSize, 8,
        0);

      final PointerBuffer chosenFormat = stack.mallocPointer(1);
      final IntBuffer formatCount = stack.mallocInt(1);

      final int chooseResult = CGL.CGLChoosePixelFormat(attributes, chosenFormat, formatCount);
      if (chooseResult != 0) {
        throw new IllegalStateException(
          String.format("CGLChoosePixelFormat failed with %d", chooseResult));
      }

      this.pixelFormat = chosenFormat.get(0);

      final PointerBuffer createdContext = stack.mallocPointer(1);
      final int createResult = CGL.CGLCreateContext(this.pixelFormat, 0L, createdContext);
      if (createResult != 0) {
        CGL.CGLDestroyPixelFormat(this.pixelFormat);
        this.pixelFormat = 0L;

        throw new IllegalStateException(
          String.format("CGLCreateContext failed with %d", createResult));
      }

      this.context = createdContext.get(0);
    }
  }

  @Override
  public void makeCurrent() {
    final int result = CGL.CGLSetCurrentContext(this.context);
    if (result != 0) {
      throw new IllegalStateException(String.format("CGLSetCurrentContext failed with %d", result));
    }

    // Reads the function pointers for the context that is current on this thread, so it has to
    // follow the call above rather than sit in the constructor.
    GL.createCapabilities();
  }

  @Override
  public void close() {
    if (this.context != 0L) {
      CGL.CGLSetCurrentContext(0L);
      CGL.CGLDestroyContext(this.context);
      this.context = 0L;
    }

    if (this.pixelFormat != 0L) {
      CGL.CGLDestroyPixelFormat(this.pixelFormat);
      this.pixelFormat = 0L;
    }
  }
}
