package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Turns a request body plus its headers into a response, for either protocol era.
 *
 * <p>This is the whole protocol in one class, and it deliberately never touches a socket: it takes
 * a string and a {@link RequestHeaders} and returns an {@link McpResponse}. That is what lets the
 * entire era matrix be checked in milliseconds from a plain {@code main()} with no port bound, no
 * Chromatik running, and no timing to get wrong.
 */
final class McpDispatcher {

  private static final String SERVER_NAME = "chromatik-mcp";

  /** Guidance a client injects once per session. Cheaper than repeating it in every description. */
  private static final String INSTRUCTIONS = """
      Drives Chromatik, a lighting workstation that renders onto a 3D point cloud rather than a \
      screen. Everything is addressed by its canonical LX path, for example \
      /lx/mixer/channel/1/pattern/1/scale, and the output of one tool is the input to the next.

      Orient before mutating: lx_project for what exists, lx_catalog for what is installed, \
      lx_docs before setting any parameter you have not set before. Parameter path keys are not \
      Java field names and are not panel labels; lx_docs gives all three, and guessing produces a \
      path that does not resolve.

      Mutations go through Chromatik's own undo history, so lx_undo reverses them. Set many \
      parameters in one lx_set rather than one per call. After changing something visual, call \
      lx_look to see the result.""";

  private final ToolRegistry registry;
  private final String serverVersion;

  McpDispatcher(ToolRegistry registry, String serverVersion) {
    this.registry = registry;
    this.serverVersion = serverVersion;
  }

  McpResponse handle(String body, RequestHeaders headers) {
    final ProtocolEra era = ProtocolEra.forVersion(headers.protocolVersion());

    if (era == null) {
      return McpResponse.badRequest(JsonRpc.error(null, JsonRpc.UNSUPPORTED_PROTOCOL_VERSION,
          "Unsupported protocol version: " + headers.protocolVersion(),
          supportedVersionsData(headers.protocolVersion())));
    }

    final McpRequest request;

    try {
      request = JsonRpc.parse(body);
    } catch (JsonRpcException malformed) {
      return McpResponse.badRequest(JsonRpc.error(null, malformed.code, malformed.getMessage(), malformed.data));
    }

    try {
      if (era == ProtocolEra.MODERN) {
        validateModernHeaders(request, headers);
      }

      final JsonObject result = route(request, era);

      // A notification has no id, so there is nothing to respond to and the specification wants a
      // bare 202. route() returns null for the ones we recognise.
      if (request.isNotification()) {
        return McpResponse.accepted();
      }

      return McpResponse.ok(JsonRpc.result(request.id(), result));

    } catch (JsonRpcException failed) {
      final JsonObject error = JsonRpc.error(request.id(), failed.code, failed.getMessage(), failed.data);

      // A header mismatch is the one protocol error the specification pins to a status code.
      return failed.code == JsonRpc.HEADER_MISMATCH || failed.code == JsonRpc.UNSUPPORTED_PROTOCOL_VERSION
          ? McpResponse.badRequest(error)
          : McpResponse.ok(error);
    }
  }

  /**
   * The modern era mirrors parts of the body into headers so that proxies can route without
   * parsing JSON, and requires the server to reject any disagreement between the two. Skipping
   * this would let a gateway route on one value while we act on another.
   */
  private void validateModernHeaders(McpRequest request, RequestHeaders headers) throws JsonRpcException {
    final String declared = request.declaredProtocolVersion();

    if (declared != null && !declared.equals(headers.protocolVersion())) {
      throw new JsonRpcException(JsonRpc.HEADER_MISMATCH, String.format(
          "MCP-Protocol-Version header is %s but the body declares %s",
          headers.protocolVersion(), declared));
    }

    if (headers.mcpMethod() == null) {
      throw new JsonRpcException(JsonRpc.HEADER_MISMATCH, "Missing required Mcp-Method header");
    }

    if (!headers.mcpMethod().equals(request.method())) {
      throw new JsonRpcException(JsonRpc.HEADER_MISMATCH, String.format(
          "Mcp-Method header is %s but the body method is %s", headers.mcpMethod(), request.method()));
    }

    // Mcp-Name mirrors params.name, and only for the calls that name a target. Requiring it
    // elsewhere, or tolerating it elsewhere, both diverge from the specification.
    final String expectedName = switch (request.method()) {
      case "tools/call" -> request.stringParam("name");
      case "prompts/get" -> request.stringParam("name");
      case "resources/read" -> request.stringParam("uri");
      default -> null;
    };

    final String actualName = headers.decodedMcpName();

    if (expectedName == null && actualName != null) {
      throw new JsonRpcException(JsonRpc.HEADER_MISMATCH,
          "Mcp-Name header was sent on " + request.method() + ", which names no target");
    }

    if (expectedName != null && !expectedName.equals(actualName)) {
      throw new JsonRpcException(JsonRpc.HEADER_MISMATCH, String.format(
          "Mcp-Name header is %s but the body names %s", actualName, expectedName));
    }
  }

  private JsonObject route(McpRequest request, ProtocolEra era) throws JsonRpcException {
    return switch (request.method()) {
      case "initialize" -> initialize(era);
      case "notifications/initialized" -> null;
      case "server/discover" -> discover();
      case "tools/list" -> toolsList(era);
      case "tools/call" -> toolsCall(request, era);
      case "ping" -> new JsonObject();
      default -> throw new JsonRpcException(JsonRpc.METHOD_NOT_FOUND, "Method not found: " + request.method());
    };
  }

  /**
   * The legacy handshake.
   *
   * <p>Answered even when the request arrived with a modern version header, because a dual-era
   * server picks its behaviour from how the client opens rather than from what it could support.
   * A client that says {@code initialize} wants legacy semantics.
   */
  private JsonObject initialize(ProtocolEra era) {
    final JsonObject result = new JsonObject();
    result.addProperty("protocolVersion", ProtocolEra.LEGACY.version);
    result.add("capabilities", capabilities());
    result.add("serverInfo", serverInfo());
    result.addProperty("instructions", INSTRUCTIONS);

    // No resultType and no cache hints: those belong to the modern shape, and a dual-era client
    // reads their presence as "this server answered as modern".
    return era == ProtocolEra.MODERN ? ModernEnvelope.complete(result) : result;
  }

  /** The modern replacement for the handshake. Servers must implement it. */
  private JsonObject discover() {
    final JsonArray versions = new JsonArray();

    for (String version : ProtocolEra.supportedVersions()) {
      versions.add(version);
    }

    final JsonObject meta = new JsonObject();
    meta.add("io.modelcontextprotocol/serverInfo", serverInfo());

    final JsonObject result = new JsonObject();
    result.add("supportedVersions", versions);
    result.add("capabilities", capabilities());
    result.add("_meta", meta);
    result.addProperty("instructions", INSTRUCTIONS);

    return ModernEnvelope.cacheable(ModernEnvelope.complete(result));
  }

  private JsonObject toolsList(ProtocolEra era) {
    final JsonObject result = new JsonObject();
    result.add("tools", this.registry.describe());

    return era == ProtocolEra.MODERN
        ? ModernEnvelope.cacheable(ModernEnvelope.complete(result))
        : result;
  }

  private JsonObject toolsCall(McpRequest request, ProtocolEra era) throws JsonRpcException {
    final String name = request.stringParam("name");

    if (name == null) {
      throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "tools/call requires a \"name\"");
    }

    final Tool tool = this.registry.get(name);

    if (tool == null) {
      throw new JsonRpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: " + name);
    }

    final JsonObject arguments = request.params().has("arguments")
        && request.params().get("arguments").isJsonObject()
            ? request.params().getAsJsonObject("arguments")
            : new JsonObject();

    try {
      return toolResult(tool.call(arguments), era);
    } catch (McpToolException refused) {
      // Not a protocol error: the model asked for something reasonable that Chromatik cannot do
      // right now. It goes back as a result so the client passes it to the model to react to.
      return errorResult(refused.getMessage(), era);
    }
  }

  /**
   * A tool's payload, as a single JSON text block.
   *
   * <p>Deliberately not also sent as {@code structuredContent}. The specification pairs that field
   * with an {@code outputSchema}, and suggests duplicating it into a text block for clients that
   * predate it; no tool here declares an output schema, so sending both would mean every response
   * carrying the same JSON twice. Measured on this server that doubled a catalogue listing from
   * roughly three thousand characters to six, and context is the scarcest thing an agent working a
   * lighting rig has: a model with thousands of points and dozens of parameters per device can
   * exhaust a window in a handful of careless calls. One copy, in the form every client renders.
   */
  private JsonObject toolResult(JsonObject payload, ProtocolEra era) {
    final JsonObject result = new JsonObject();
    result.add("content", textContent(JsonRpc.GSON.toJson(payload)));
    result.addProperty("isError", false);

    return era == ProtocolEra.MODERN ? ModernEnvelope.complete(result) : result;
  }

  private JsonObject errorResult(String message, ProtocolEra era) {
    final JsonObject result = new JsonObject();
    result.add("content", textContent(message));
    result.addProperty("isError", true);

    return era == ProtocolEra.MODERN ? ModernEnvelope.complete(result) : result;
  }

  private JsonArray textContent(String text) {
    final JsonObject block = new JsonObject();
    block.addProperty("type", "text");
    block.addProperty("text", text);

    final JsonArray content = new JsonArray();
    content.add(block);

    return content;
  }

  private JsonObject capabilities() {
    // listChanged is not advertised: the tool set is fixed once the plugin starts, and claiming
    // otherwise would invite a client to open a subscriptions/listen stream for notifications
    // that never come.
    final JsonObject capabilities = new JsonObject();
    capabilities.add("tools", new JsonObject());

    return capabilities;
  }

  private JsonObject serverInfo() {
    final JsonObject info = new JsonObject();
    info.addProperty("name", SERVER_NAME);
    info.addProperty("version", this.serverVersion);

    return info;
  }

  private JsonObject supportedVersionsData(String requested) {
    final JsonArray supported = new JsonArray();

    for (String version : ProtocolEra.supportedVersions()) {
      supported.add(version);
    }

    final JsonObject data = new JsonObject();
    data.add("supported", supported);
    data.addProperty("requested", requested);

    return data;
  }
}
