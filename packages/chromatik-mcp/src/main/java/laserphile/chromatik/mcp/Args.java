package laserphile.chromatik.mcp;

import com.google.gson.JsonObject;

/**
 * Reading a tool's arguments, with failures phrased for the model rather than for a debugger.
 *
 * <p>Everything here throws {@link McpToolException}, so a wrong argument comes back as a result
 * the model can read and correct rather than as a protocol error it never sees.
 */
final class Args {

  static String requireString(JsonObject arguments, String name) throws McpToolException {
    final String value = optionalString(arguments, name, null);

    if (value == null || value.isBlank()) {
      throw new McpToolException("missing required argument \"" + name + "\"");
    }

    return value;
  }

  static String optionalString(JsonObject arguments, String name, String fallback) {
    return arguments.has(name) && arguments.get(name).isJsonPrimitive()
        ? arguments.get(name).getAsString()
        : fallback;
  }

  static boolean optionalBoolean(JsonObject arguments, String name, boolean fallback) {
    if (!arguments.has(name) || !arguments.get(name).isJsonPrimitive()) {
      return fallback;
    }

    final var primitive = arguments.getAsJsonPrimitive(name);

    return primitive.isBoolean() ? primitive.getAsBoolean() : Boolean.parseBoolean(primitive.getAsString());
  }

  static int optionalInt(JsonObject arguments, String name, int fallback) throws McpToolException {
    if (!arguments.has(name) || !arguments.get(name).isJsonPrimitive()) {
      return fallback;
    }

    try {
      return arguments.get(name).getAsInt();
    } catch (NumberFormatException notANumber) {
      throw new McpToolException("argument \"" + name + "\" must be a whole number");
    }
  }

  /** Round to four significant figures, which is well past what any light is sensitive to. */
  static double tidy(double value) {
    if (!Double.isFinite(value) || value == 0) {
      return 0;
    }

    final java.math.BigDecimal rounded =
        new java.math.BigDecimal(value).round(new java.math.MathContext(4));

    return rounded.doubleValue();
  }

  private Args() {
  }
}
