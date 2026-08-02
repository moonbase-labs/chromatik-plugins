package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builders for the JSON Schema of a tool's arguments.
 *
 * <p>Only here to keep each tool's schema readable as four lines of data rather than forty
 * {@code addProperty} calls. Every schema this produces is a 2020-12 object schema, which is what
 * the specification defaults to when no {@code $schema} is given.
 */
final class Schema {

  /** An object schema. Arguments alternate name and property schema. */
  static JsonObject object(Object... nameThenProperty) {
    final JsonObject properties = new JsonObject();

    for (int index = 0; index < nameThenProperty.length; index += 2) {
      properties.add((String) nameThenProperty[index], (JsonObject) nameThenProperty[index + 1]);
    }

    final JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    schema.add("properties", properties);

    return schema;
  }

  /** An object schema taking no arguments at all. */
  static JsonObject noArguments() {
    final JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    schema.addProperty("additionalProperties", false);

    return schema;
  }

  /** Mark some of an object schema's properties required. Returns the same schema. */
  static JsonObject required(JsonObject schema, String... names) {
    final JsonArray required = new JsonArray();

    for (String name : names) {
      required.add(name);
    }

    schema.add("required", required);

    return schema;
  }

  static JsonObject string(String description) {
    return leaf("string", description);
  }

  static JsonObject bool(String description, boolean fallback) {
    final JsonObject property = leaf("boolean", description);
    property.addProperty("default", fallback);

    return property;
  }

  static JsonObject integer(String description, int minimum, int maximum, int fallback) {
    final JsonObject property = leaf("integer", description);
    property.addProperty("minimum", minimum);
    property.addProperty("maximum", maximum);
    property.addProperty("default", fallback);

    return property;
  }

  /** A string constrained to a fixed set. The first entry is the default. */
  static JsonObject choice(String description, String... options) {
    final JsonObject property = leaf("string", description);
    final JsonArray allowed = new JsonArray();

    for (String option : options) {
      allowed.add(option);
    }

    property.add("enum", allowed);
    property.addProperty("default", options[0]);

    return property;
  }

  private static JsonObject leaf(String type, String description) {
    final JsonObject property = new JsonObject();
    property.addProperty("type", type);
    property.addProperty("description", description);

    return property;
  }

  private Schema() {
  }
}
