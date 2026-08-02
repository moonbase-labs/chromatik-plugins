package laserphile.chromatik.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * The JSON-RPC 2.0 envelope, which is the layer underneath every MCP message.
 *
 * <p>Parsing and envelope building live here so that {@link McpDispatcher} only ever deals in
 * already-validated requests, and so the wire format has exactly one definition.
 */
final class JsonRpc {

  static final int PARSE_ERROR = -32700;
  static final int INVALID_REQUEST = -32600;
  static final int METHOD_NOT_FOUND = -32601;

  /**
   * Invalid parameters. Also what an unknown tool name gets: the specification's own example for
   * "Unknown tool" uses this rather than METHOD_NOT_FOUND, because the method (`tools/call`) was
   * found and it was the argument that was wrong.
   */
  static final int INVALID_PARAMS = -32602;

  static final int INTERNAL_ERROR = -32603;

  /** Modern era: the HTTP headers disagree with the body, or a required one is missing. */
  static final int HEADER_MISMATCH = -32020;

  /** Modern era: the client asked for a protocol version this server does not implement. */
  static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

  /**
   * One shared instance. serializeNulls is off, so an absent optional field is simply absent
   * rather than an explicit null the client has to think about, and HTML escaping is off because
   * this is JSON on a socket, not markup: without it gson turns an apostrophe in a parameter
   * description into ' and the model reads the escape.
   */
  static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  /**
   * Read a request off the wire.
   *
   * @throws JsonRpcException if the body is not a single well-formed JSON-RPC request object
   */
  static McpRequest parse(String body) throws JsonRpcException {
    final JsonElement parsed;

    try {
      parsed = GSON.fromJson(body, JsonElement.class);
    } catch (JsonParseException malformed) {
      throw new JsonRpcException(PARSE_ERROR, "Request body is not valid JSON");
    }

    if (parsed == null || parsed.isJsonNull()) {
      throw new JsonRpcException(PARSE_ERROR, "Request body is empty");
    }

    // Batching was removed from MCP in the 2025-06-18 revision and is absent from both eras this
    // server speaks, so an array is a client bug rather than something to half-support.
    if (parsed.isJsonArray()) {
      throw new JsonRpcException(INVALID_REQUEST, "Batched requests are not supported");
    }

    if (!parsed.isJsonObject()) {
      throw new JsonRpcException(INVALID_REQUEST, "Request body must be a JSON object");
    }

    final JsonObject object = parsed.getAsJsonObject();

    if (!hasString(object, "jsonrpc") || !"2.0".equals(object.get("jsonrpc").getAsString())) {
      throw new JsonRpcException(INVALID_REQUEST, "Missing or unsupported \"jsonrpc\" version, expected \"2.0\"");
    }

    if (!hasString(object, "method")) {
      throw new JsonRpcException(INVALID_REQUEST, "Missing \"method\"");
    }

    final JsonObject params = object.has("params") && object.get("params").isJsonObject()
        ? object.getAsJsonObject("params")
        : new JsonObject();

    // No id means a notification: the client is not waiting for a result and must not be sent one.
    final JsonElement id = object.has("id") && !object.get("id").isJsonNull() ? object.get("id") : null;

    return new McpRequest(id, object.get("method").getAsString(), params);
  }

  /** A successful response carrying {@code result}. */
  static JsonObject result(JsonElement id, JsonObject result) {
    final JsonObject response = envelope(id);
    response.add("result", result);

    return response;
  }

  /** An error response. {@code data} may be null. */
  static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
    final JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);

    if (data != null) {
      error.add("data", data);
    }

    final JsonObject response = envelope(id);
    response.add("error", error);

    return response;
  }

  private static JsonObject envelope(JsonElement id) {
    final JsonObject response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");

    // An error raised before the id could be read still needs a well-formed envelope, and
    // JSON-RPC says to use a null id for that case rather than omitting the member.
    response.add("id", id != null ? id : JsonNull.INSTANCE);

    return response;
  }

  private static boolean hasString(JsonObject object, String member) {
    return object.has(member)
        && object.get(member).isJsonPrimitive()
        && object.getAsJsonPrimitive(member).isString();
  }

  private JsonRpc() {
  }
}
