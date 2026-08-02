package laserphile.chromatik.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A parsed JSON-RPC request.
 *
 * @param id the request id, or null when this is a notification and no response may be sent
 * @param method the JSON-RPC method name, always present
 * @param params the params object, never null: an absent or non-object {@code params} becomes empty
 */
record McpRequest(JsonElement id, String method, JsonObject params) {

  boolean isNotification() {
    return this.id == null;
  }

  /**
   * The modern era's per-request metadata, or an empty object.
   *
   * <p>Carries {@code io.modelcontextprotocol/protocolVersion}, {@code clientInfo} and
   * {@code clientCapabilities}. The dot-and-slash keys are literal, not a nesting convention.
   */
  JsonObject meta() {
    return this.params.has("_meta") && this.params.get("_meta").isJsonObject()
        ? this.params.getAsJsonObject("_meta")
        : new JsonObject();
  }

  /** The protocol version declared in the body, or null if absent. */
  String declaredProtocolVersion() {
    final JsonObject meta = meta();
    final String key = "io.modelcontextprotocol/protocolVersion";

    return meta.has(key) && meta.get(key).isJsonPrimitive() ? meta.get(key).getAsString() : null;
  }

  /** A string param, or null. Used for the {@code Mcp-Name} header cross-check. */
  String stringParam(String name) {
    return this.params.has(name) && this.params.get(name).isJsonPrimitive()
        ? this.params.get(name).getAsString()
        : null;
  }
}
