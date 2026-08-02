package laserphile.chromatik.shader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a shader's {@code uniform} declarations out of its source text.
 *
 * This is deliberately plain text work rather than anything to do with OpenGL. Asking the linked
 * program what uniforms it has would be more authoritative, but it can only be asked on the render
 * thread and only after a successful compile, and the controls have to exist before either: a
 * project being opened has to rebuild its knobs and apply saved values to them without a GPU
 * anywhere in the picture.
 *
 * The trade is that a uniform the compiler optimises away still gets a knob. That is the better
 * failure: a control that does nothing is easier to understand than a control that vanishes
 * depending on how a driver felt about the arithmetic.
 */
final class UniformParser {

  /**
   * A declaration and whatever trails it on the same line.
   *
   * The optional precision qualifier is matched so that {@code uniform mediump float x;} is read
   * as a float rather than as a variable called "float". Array declarations are matched so they
   * can be recognised and skipped rather than mistaken for a scalar.
   */
  private static final Pattern DECLARATION = Pattern.compile(
    "(?m)^\\s*uniform\\s+(?:lowp\\s+|mediump\\s+|highp\\s+)?(\\w+)\\s+([^;]+);(.*)$");

  private static final Pattern RANGE_ANNOTATION =
    Pattern.compile("@range\\s*\\(\\s*(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*\\)");
  private static final Pattern DEFAULT_ANNOTATION =
    Pattern.compile("@default\\s*\\(\\s*(-?[\\d.]+)\\s*\\)");

  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
  private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");

  private UniformParser() {
    // Static helpers only.
  }

  /**
   * Every uniform the source declares, in declaration order.
   *
   * Order matters downstream: it decides which uniform reaches which knob, and a control surface
   * only has eight. Declaring the interesting one first is how an author puts it under a hand.
   */
  static List<UniformDeclaration> parse(String source) {
    final List<UniformDeclaration> declarations = new ArrayList<>();
    final Matcher matcher = DECLARATION.matcher(stripBlockComments(source));

    while (matcher.find()) {
      final String type = matcher.group(1);
      final String names = matcher.group(2);
      final String trailing = matcher.group(3);

      // One declaration can name several: `uniform float depth, rate;`. They share the annotation,
      // which is the only sensible reading of a single trailing comment.
      for (String rawName : names.split(",")) {
        final String name = rawName.trim();

        if (name.isEmpty() || name.contains("[")) {
          continue; // an array, which has no single knob to offer
        }

        declarations.add(withAnnotations(name, type, trailing));
      }
    }

    return declarations;
  }

  /** Just the names, for deciding which builtin declarations the prologue still has to add. */
  static Set<String> declaredNames(String source) {
    final Set<String> names = new LinkedHashSet<>();

    for (UniformDeclaration declaration : parse(source)) {
      names.add(declaration.name());
    }

    return names;
  }

  private static UniformDeclaration withAnnotations(String name, String type, String trailing) {
    double minimum = UniformDeclaration.DEFAULT_MINIMUM;
    double maximum = UniformDeclaration.DEFAULT_MAXIMUM;

    final Matcher range = RANGE_ANNOTATION.matcher(trailing);
    if (range.find()) {
      minimum = Double.parseDouble(range.group(1));
      maximum = Double.parseDouble(range.group(2));
    }

    // Halfway up whatever the range turned out to be, so an annotated @range with no @default
    // still rests somewhere sensible rather than at zero.
    double defaultValue = minimum + ((maximum - minimum) * UniformDeclaration.DEFAULT_VALUE);

    final Matcher declared = DEFAULT_ANNOTATION.matcher(trailing);
    if (declared.find()) {
      defaultValue = Double.parseDouble(declared.group(1));
    }

    return new UniformDeclaration(name, type, minimum, maximum, defaultValue);
  }

  /**
   * Blank out comments so nothing inside one is read as code.
   *
   * Replacing rather than deleting, so that every character keeps its position and any line count
   * taken afterwards still matches the file the author is looking at.
   */
  static String stripComments(String source) {
    return blankOut(LINE_COMMENT, blankOut(BLOCK_COMMENT, source));
  }

  private static String stripBlockComments(String source) {
    return blankOut(BLOCK_COMMENT, source);
  }

  private static String blankOut(Pattern pattern, String source) {
    final Matcher matcher = pattern.matcher(source);
    final StringBuilder result = new StringBuilder(source);

    while (matcher.find()) {
      for (int index = matcher.start(); index < matcher.end(); index++) {
        if (result.charAt(index) != '\n') {
          result.setCharAt(index, ' ');
        }
      }
    }

    return result.toString();
  }
}
