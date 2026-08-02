package laserphile.chromatik.mcp;

import com.google.gson.JsonObject;

/**
 * What the dispatcher decided, before anything knows it is travelling over HTTP.
 *
 * <p>Keeping the status alongside the body is what lets the dispatcher be driven directly from a
 * test harness with no socket: every rule that would otherwise be split between "what the handler
 * does" and "what the protocol says" is decided in one place and is inspectable as a value.
 *
 * @param status the HTTP status to send
 * @param body the JSON body, or null to send no content at all
 */
record McpResponse(int status, JsonObject body) {

  static McpResponse ok(JsonObject body) {
    return new McpResponse(200, body);
  }

  /**
   * A notification the server accepted. The specification requires 202 with an empty body: a
   * notification has no id, so there is nothing to respond to.
   */
  static McpResponse accepted() {
    return new McpResponse(202, null);
  }

  static McpResponse badRequest(JsonObject body) {
    return new McpResponse(400, body);
  }

  String serialize() {
    return this.body == null ? "" : JsonRpc.GSON.toJson(this.body);
  }
}
