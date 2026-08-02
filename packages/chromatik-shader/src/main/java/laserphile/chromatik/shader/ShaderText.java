package laserphile.chromatik.shader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a shader file's contents into something a core profile will compile, without editing what
 * the author wrote.
 *
 * Shaders worth running were mostly written for somewhere else. A Processing sketch's shader has
 * no {@code #version} and writes {@code gl_FragColor}, both of which a core profile rejects. A
 * Shadertoy shader has no {@code main} at all, only a {@code mainImage} the site calls for it.
 * Rather than ask for either to be ported, this puts a prologue in front that makes the old
 * spellings mean the right thing, and appends the call Shadertoy would have made.
 *
 * The author's text is never rewritten, only surrounded. That matters for more than tidiness: a
 * {@code #line} directive at the end of the prologue puts the numbering back to 1, so when the
 * driver rejects something it names the line the author is looking at in their editor rather than
 * a line in a file they have never seen.
 */
final class ShaderText {

  /**
   * The Shadertoy entry point, matched as a definition rather than as a word.
   *
   * The distinction is not hypothetical. A shader ported by hand may well carry
   * {@code #define mainImage main} near the top, left over from being adapted the other way, and
   * a substring test would read that as a Shadertoy shader and append a call to a function that
   * is really just {@code main}, which recurses. Requiring a return type and an open bracket at
   * the start of a line is what tells a definition from a mention.
   */
  private static final Pattern MAIN_IMAGE_DEFINITION =
    Pattern.compile("(?m)^\\s*void\\s+mainImage\\s*\\(");

  private static final Pattern MAIN_DEFINITION =
    Pattern.compile("(?m)^\\s*void\\s+main\\s*\\(");

  /** Matched so that the author's own version directive is kept and ours is not added twice. */
  private static final Pattern VERSION_DIRECTIVE =
    Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+.*$");

  /** What a core profile needs and the old dialects assume, minus anything already declared. */
  private static final String OUTPUT_NAME = "chromatikFragColor";

  private static final String DEFAULT_VERSION = "#version 330 core";

  /**
   * Uniforms this plugin feeds rather than exposes, under both the plain and the Shadertoy
   * spelling, so that a file written for either finds what it expects.
   *
   * Declared only when the shader has not declared it already, since a second declaration of the
   * same name is a compile error and most of these files declare their own.
   */
  private static final Map<String, String> BUILT_IN_UNIFORMS = builtInUniforms();

  private static Map<String, String> builtInUniforms() {
    final Map<String, String> uniforms = new LinkedHashMap<>();

    uniforms.put("time", "float");
    uniforms.put("iTime", "float");
    uniforms.put("resolution", "vec2");
    uniforms.put("iResolution", "vec3");
    uniforms.put("iFrame", "int");

    return uniforms;
  }

  /** Names the plugin drives itself, so they never become knobs. */
  static boolean isBuiltIn(String uniformName) {
    return BUILT_IN_UNIFORMS.containsKey(uniformName);
  }

  static Iterable<String> builtInNames() {
    return BUILT_IN_UNIFORMS.keySet();
  }

  private ShaderText() {
    // Static helpers only.
  }

  /** Raised when a file is not a fragment shader this can do anything with. */
  static final class UnusableShaderException extends Exception {
    UnusableShaderException(String message) {
      super(message);
    }
  }

  /**
   * The full fragment source to hand the compiler.
   *
   * @throws UnusableShaderException when the file defines neither entry point, which is what a
   *     vertex shader, half of a shader pair, or an accidentally chosen file looks like from here
   */
  static String prepare(String userSource) throws UnusableShaderException {
    // Detection runs against a copy with the comments blanked, so a commented-out signature (which
    // both of the shaders this was built against happen to carry) is not mistaken for the real one.
    final String code = UniformParser.stripComments(userSource);

    final boolean definesMain = MAIN_DEFINITION.matcher(code).find();
    final boolean definesMainImage = MAIN_IMAGE_DEFINITION.matcher(code).find();

    if (!definesMain && !definesMainImage) {
      throw new UnusableShaderException(
        "no entry point: expected either 'void main()' or Shadertoy's 'void mainImage(out vec4, "
          + "in vec2)'. A vertex shader, or one half of a vertex and fragment pair, will look "
          + "like this.");
    }

    // main wins when a file has both. A hand-ported Shadertoy shader keeps its original mainImage
    // around as dead code often enough that preferring it would call the wrong one.
    final boolean wrapAsShadertoy = !definesMain;

    final Set<String> alreadyDeclared = UniformParser.declaredNames(userSource);
    final Matcher version = VERSION_DIRECTIVE.matcher(userSource);

    final StringBuilder prepared = new StringBuilder();
    final String body;
    final int firstBodyLine;

    if (version.find()) {
      // Theirs has to stay first, and nothing may precede it, so the prologue slots in behind it
      // and the body resumes on the line after.
      prepared.append(userSource, version.start(), version.end()).append('\n');
      body = userSource.substring(version.end());
      firstBodyLine = lineNumberOf(userSource, version.end()) + 1;
    } else {
      prepared.append(DEFAULT_VERSION).append('\n');
      body = userSource;
      firstBodyLine = 1;
    }

    appendPrologue(prepared, alreadyDeclared);

    prepared.append("#line ").append(firstBodyLine).append('\n');
    prepared.append(body);

    if (wrapAsShadertoy) {
      prepared.append("\nvoid main() {\n  mainImage(")
        .append(OUTPUT_NAME)
        .append(", gl_FragCoord.xy);\n}\n");
    }

    return prepared.toString();
  }

  private static void appendPrologue(StringBuilder prepared, Set<String> alreadyDeclared) {
    prepared.append("out vec4 ").append(OUTPUT_NAME).append(";\n");

    // Legal on every driver this has been tried against, and better than rewriting the text: the
    // author's characters stay where they are, so #line keeps error messages honest.
    prepared.append("#define gl_FragColor ").append(OUTPUT_NAME).append('\n');

    // Removed in core profiles, and used by essentially every shader old enough to need this
    // prologue in the first place.
    prepared.append("#define texture2D texture\n");
    prepared.append("#define textureCube texture\n");

    for (Map.Entry<String, String> uniform : BUILT_IN_UNIFORMS.entrySet()) {
      if (!alreadyDeclared.contains(uniform.getKey())) {
        prepared.append("uniform ").append(uniform.getValue()).append(' ')
          .append(uniform.getKey()).append(";\n");
      }
    }
  }

  private static int lineNumberOf(String source, int offset) {
    int line = 1;

    for (int index = 0; index < offset && index < source.length(); index++) {
      if (source.charAt(index) == '\n') {
        line++;
      }
    }

    return line;
  }
}
