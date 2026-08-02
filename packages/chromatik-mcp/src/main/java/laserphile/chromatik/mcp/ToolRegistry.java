package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tools this server offers, in the order a client sees them.
 *
 * <p>Insertion-ordered on purpose. The specification asks servers to return tools deterministically
 * so clients can cache the list and so a model's prompt cache keeps hitting, and the order is also
 * a weak steer: read tools are registered first because an agent that orients before it mutates
 * makes better decisions and fewer undo calls.
 */
final class ToolRegistry {

  private final Map<String, Tool> tools = new LinkedHashMap<>();

  void add(Tool tool) {
    final Tool clash = this.tools.put(tool.name(), tool);

    if (clash != null) {
      throw new IllegalStateException("Two tools registered as " + tool.name());
    }
  }

  /** The named tool, or null. A null here becomes an unknown-tool error, not a crash. */
  Tool get(String name) {
    return this.tools.get(name);
  }

  int size() {
    return this.tools.size();
  }

  /** The {@code tools} array of a {@code tools/list} result. */
  JsonArray describe() {
    final JsonArray described = new JsonArray();

    for (Tool tool : this.tools.values()) {
      final JsonObject entry = new JsonObject();
      entry.addProperty("name", tool.name());
      entry.addProperty("title", tool.title());
      entry.addProperty("description", tool.description());
      entry.add("inputSchema", tool.inputSchema());

      // readOnlyHint lets a client skip a confirmation prompt for a call that cannot change
      // anything. It is a hint, not a permission: the server still decides what a tool may do.
      final JsonObject annotations = new JsonObject();
      annotations.addProperty("readOnlyHint", tool.readOnly());
      entry.add("annotations", annotations);

      described.add(entry);
    }

    return described;
  }
}
