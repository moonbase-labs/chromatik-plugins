package laserphile.chromatik.shader;

import java.nio.ByteBuffer;

import laserphile.chromatik.core.VideoFrame;

import static org.lwjgl.opengl.GL33.*;

/**
 * Compiles a fragment shader and renders it into an offscreen framebuffer, one square frame at a
 * time, reading the result back as packed ARGB.
 *
 * Every method needs the context current on the calling thread, so all of them run on the frame
 * pipeline's thread and none of them are safe to call from the engine.
 *
 * The geometry is a single triangle large enough to cover the viewport, with its corners computed
 * from {@code gl_VertexID}. That means no vertex buffer to allocate or bind, and one fewer thing
 * to leak. A core profile still insists on a bound vertex array object even when the shader reads
 * no attributes from it, which is what {@link #vertexArray} is for.
 */
final class ShaderRenderer {

  private static final String VERTEX_SHADER = """
    #version 330 core
    void main() {
      vec2 corners[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
      gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0);
    }
    """;

  private final int edge;

  /**
   * Read straight out of the framebuffer, then flipped into the frame handed upstream. Held rather
   * than allocated per frame because at 512 square this is a megabyte a frame at the engine rate.
   */
  private final int[] readbackPixels;

  private int vertexArray;
  private int framebuffer;
  private int colorTexture;

  /**
   * Zero until something compiles. A failed recompile leaves the previous program in place, so a
   * typo mid-show costs the change rather than the output.
   */
  private int program;

  private int timeLocation = -1;
  private int resolutionLocation = -1;

  ShaderRenderer(int edge) {
    this.edge = edge;
    this.readbackPixels = new int[edge * edge];
  }

  /** Allocate the framebuffer this renders into. Once, after the context is current. */
  void initialize() {
    this.vertexArray = glGenVertexArrays();
    glBindVertexArray(this.vertexArray);

    this.colorTexture = glGenTextures();
    glBindTexture(GL_TEXTURE_2D, this.colorTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, this.edge, this.edge, 0, GL_RGBA, GL_UNSIGNED_BYTE,
      (ByteBuffer) null);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    this.framebuffer = glGenFramebuffers();
    glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.colorTexture,
      0);

    final int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
      throw new IllegalStateException(
        String.format("offscreen framebuffer incomplete: 0x%s", Integer.toHexString(status)));
    }

    glViewport(0, 0, this.edge, this.edge);
  }

  /**
   * Build a program from the given fragment source and swap it in.
   *
   * @return null when it compiled and linked, otherwise the driver's log. The caller decides what
   *     to do with the text; this class only guarantees that a failure changes nothing.
   */
  String compile(String fragmentSource) {
    final int vertexShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShader, VERTEX_SHADER);
    glCompileShader(vertexShader);

    if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) != GL_TRUE) {
      final String log = glGetShaderInfoLog(vertexShader);
      glDeleteShader(vertexShader);

      // Nothing the user wrote can break this one, so a failure here is ours.
      return String.format("internal vertex shader failed to compile: %s", log);
    }

    final int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShader, fragmentSource);
    glCompileShader(fragmentShader);

    if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS) != GL_TRUE) {
      final String log = glGetShaderInfoLog(fragmentShader);
      glDeleteShader(vertexShader);
      glDeleteShader(fragmentShader);

      return log;
    }

    final int linked = glCreateProgram();
    glAttachShader(linked, vertexShader);
    glAttachShader(linked, fragmentShader);
    glLinkProgram(linked);

    // Attached shaders are reference-counted by the program, so they can go now either way.
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    if (glGetProgrami(linked, GL_LINK_STATUS) != GL_TRUE) {
      final String log = glGetProgramInfoLog(linked);
      glDeleteProgram(linked);

      return log;
    }

    if (this.program != 0) {
      glDeleteProgram(this.program);
    }

    this.program = linked;
    this.timeLocation = glGetUniformLocation(linked, "time");
    this.resolutionLocation = glGetUniformLocation(linked, "resolution");

    return null;
  }

  /** True once something has compiled, and so once there is anything to render. */
  boolean hasProgram() {
    return this.program != 0;
  }

  /**
   * Draw one frame and read it back.
   *
   * The rows come out of OpenGL bottom-first, because its framebuffer origin is bottom-left, and
   * go into the frame top-first, because that is what the projection stage samples. Hence the
   * reversed copy rather than a straight one.
   */
  VideoFrame render(double timeSeconds, long mediaTimeMs) {
    glUseProgram(this.program);

    // A shader that declares neither reports -1 for both, and glUniform on -1 is a documented
    // no-op, so nothing here needs to know which uniforms the source happened to use.
    if (this.timeLocation >= 0) {
      glUniform1f(this.timeLocation, (float) timeSeconds);
    }
    if (this.resolutionLocation >= 0) {
      glUniform2f(this.resolutionLocation, this.edge, this.edge);
    }

    glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    // BGRA with the reversed packing lands as 0xAARRGGBB on a little-endian host, which is exactly
    // what VideoFrame promises, so the pixels need no further shuffling.
    glReadPixels(0, 0, this.edge, this.edge, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
      this.readbackPixels);

    final int[] argb = new int[this.readbackPixels.length];
    for (int row = 0; row < this.edge; row++) {
      System.arraycopy(
        this.readbackPixels, row * this.edge,
        argb, (this.edge - 1 - row) * this.edge,
        this.edge);
    }

    return new VideoFrame(argb, this.edge, this.edge, mediaTimeMs);
  }

  void dispose() {
    if (this.program != 0) {
      glDeleteProgram(this.program);
      this.program = 0;
    }
    if (this.framebuffer != 0) {
      glDeleteFramebuffers(this.framebuffer);
      this.framebuffer = 0;
    }
    if (this.colorTexture != 0) {
      glDeleteTextures(this.colorTexture);
      this.colorTexture = 0;
    }
    if (this.vertexArray != 0) {
      glDeleteVertexArrays(this.vertexArray);
      this.vertexArray = 0;
    }
  }
}
