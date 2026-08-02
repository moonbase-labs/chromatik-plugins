package laserphile.chromatik.mcp;

import com.google.gson.JsonObject;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * Drives the protocol through both eras with no socket, no port and no Chromatik.
 *
 * <p>{@link McpDispatcher} takes a string and returns a value, which is what makes this possible:
 * the entire era matrix runs in milliseconds with nothing to start up and no timing to get wrong.
 * The release gate calls {@link #run()} against the built jar, so a change that breaks the wire
 * format fails the build rather than a conversation.
 *
 * <p>Public for one reason only: {@code ci/McpProtocolCheck.java} lives in the default package and
 * has to reach it. It registers as no component type, so Chromatik's scan passes over it.
 */
public final class McpSelfCheck {

  private static final String MODERN = "2026-07-28";
  private static final String LEGACY = "2025-11-25";

  private int failures = 0;
  private final boolean verbose;

  private McpSelfCheck(boolean verbose) {
    this.verbose = verbose;
  }

  /** Runs every check and returns how many failed. Zero means green. */
  public static int run() {
    return run(true);
  }

  public static int run(boolean verbose) {
    final McpSelfCheck check = new McpSelfCheck(verbose);
    check.checkAll();

    return check.failures;
  }

  public static void main(String[] args) {
    System.exit(run() == 0 ? 0 : 1);
  }

  private void checkAll() {
    final ToolRegistry registry = new ToolRegistry();
    registry.add(new StubTool());

    final McpDispatcher dispatcher = new McpDispatcher(registry, "selfcheck");

    legacy(dispatcher);
    modern(dispatcher);
    headerValidation(dispatcher);
    malformed(dispatcher);
    errorChannels(dispatcher);
  }

  private void legacy(McpDispatcher dispatcher) {
    final McpResponse handshake = dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", headers(null, null, null));
    final JsonObject result = resultOf(handshake);

    expect("legacy initialize is 200", handshake.status() == 200);
    expect("legacy negotiates " + LEGACY, LEGACY.equals(result.get("protocolVersion").getAsString()));
    expect("legacy carries serverInfo", result.has("serverInfo"));

    // The presence of resultType is how a dual-era client tells which era answered, so putting it
    // on a legacy result would be actively misleading rather than merely redundant.
    expect("legacy carries no resultType", !result.has("resultType"));

    final McpResponse notification = dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", headers(null, null, null));

    expect("notification is 202 with no body",
        notification.status() == 202 && notification.body() == null);

    final JsonObject list = resultOf(dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", headers(LEGACY, null, null)));

    expect("legacy tools/list has no cache hints", !list.has("ttlMs") && !list.has("cacheScope"));
    expect("legacy tools/list lists the tool", list.getAsJsonArray("tools").size() == 1);
  }

  private void modern(McpDispatcher dispatcher) {
    final JsonObject discover = resultOf(dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":\"d\",\"method\":\"server/discover\",\"params\":{}}",
        headers(MODERN, "server/discover", null)));

    expect("discover resultType=complete", "complete".equals(discover.get("resultType").getAsString()));
    expect("discover advertises both eras", discover.getAsJsonArray("supportedVersions").size() == 2);
    expect("discover carries ttlMs >= 0", discover.get("ttlMs").getAsLong() >= 0);
    expect("discover cacheScope=public", "public".equals(discover.get("cacheScope").getAsString()));
    expect("discover puts serverInfo in _meta",
        discover.getAsJsonObject("_meta").has("io.modelcontextprotocol/serverInfo"));

    final JsonObject list = resultOf(dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}",
        headers(MODERN, "tools/list", null)));

    expect("modern tools/list is complete and cacheable",
        list.has("resultType") && list.has("ttlMs") && list.has("cacheScope"));

    final JsonObject called = resultOf(dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"lx_stub\",\"arguments\":{\"echo\":\"hi\"}}}",
        headers(MODERN, "tools/call", "lx_stub")));

    expect("tools/call is not an error", !called.get("isError").getAsBoolean());

    // Sending the payload as both structuredContent and text would double every response, and no
    // tool here declares an output schema that would justify it.
    expect("tools/call sends the payload once", !called.has("structuredContent"));
    expect("tools/call echoes through the text block",
        called.getAsJsonArray("content").get(0).getAsJsonObject()
            .get("text").getAsString().contains("\"echo\":\"hi\""));
  }

  private void headerValidation(McpDispatcher dispatcher) {
    expectHeaderMismatch(dispatcher, "missing Mcp-Method",
        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{}}",
        headers(MODERN, null, null));

    expectHeaderMismatch(dispatcher, "Mcp-Method disagrees with the body",
        "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\",\"params\":{}}",
        headers(MODERN, "tools/call", null));

    expectHeaderMismatch(dispatcher, "Mcp-Name sent where nothing is named",
        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"params\":{}}",
        headers(MODERN, "tools/list", "lx_stub"));

    expectHeaderMismatch(dispatcher, "Mcp-Name disagrees with params.name",
        "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"lx_stub\"}}",
        headers(MODERN, "tools/call", "lx_other"));

    expectHeaderMismatch(dispatcher, "body _meta version disagrees with the header",
        "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{\"_meta\":"
            + "{\"io.modelcontextprotocol/protocolVersion\":\"2025-11-25\"}}}",
        headers(MODERN, "tools/list", null));

    // A name that is not header-safe ASCII arrives base64-wrapped and must be decoded before it is
    // compared, or a legitimate call is rejected as a mismatch.
    final String encoded = "=?base64?"
        + Base64.getEncoder().encodeToString("lx_stub".getBytes(StandardCharsets.UTF_8)) + "?=";

    expect("base64 Mcp-Name is decoded before comparison", dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"lx_stub\"}}",
        headers(MODERN, "tools/call", encoded)).status() == 200);

    final McpResponse unsupported = dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\",\"params\":{}}",
        headers("2099-01-01", "tools/list", null));

    expect("an unknown protocol version is 400 / -32022",
        unsupported.status() == 400
            && unsupported.body().getAsJsonObject("error").get("code").getAsInt() == -32022);
    expect("an unknown version names what is supported",
        unsupported.body().getAsJsonObject("error").getAsJsonObject("data")
            .getAsJsonArray("supported").size() == 2);
  }

  private void malformed(McpDispatcher dispatcher) {
    expectCode(dispatcher, "not JSON", "this is not json", -32700);
    expectCode(dispatcher, "an empty body", "", -32700);
    expectCode(dispatcher, "a batched array", "[{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}]", -32600);
    expectCode(dispatcher, "a missing jsonrpc member", "{\"id\":1,\"method\":\"ping\"}", -32600);
    expectCode(dispatcher, "a missing method", "{\"jsonrpc\":\"2.0\",\"id\":1}", -32600);
    expectCode(dispatcher, "an unknown method",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope/nope\"}", -32601);
    expectCode(dispatcher, "an unknown tool",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"lx_gone\"}}", -32602);
  }

  private void errorChannels(McpDispatcher dispatcher) {
    final McpResponse refused = dispatcher.handle(
        "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"lx_stub\",\"arguments\":{\"fail\":true}}}", headers(null, null, null));

    // A tool that cannot do what was asked is not a protocol fault. It comes back as a result so
    // the client hands it to the model, which is the only form the model can act on.
    expect("a refused tool is still 200", refused.status() == 200);
    expect("a refused tool is a result, not an error", !refused.body().has("error"));
    expect("a refused tool sets isError", resultOf(refused).get("isError").getAsBoolean());
  }

  // ---- helpers ------------------------------------------------------------

  private RequestHeaders headers(String version, String method, String name) {
    return new RequestHeaders(version, method, name, null);
  }

  private JsonObject resultOf(McpResponse response) {
    return response.body().getAsJsonObject("result");
  }

  private void expectHeaderMismatch(McpDispatcher dispatcher, String what, String body, RequestHeaders headers) {
    final McpResponse response = dispatcher.handle(body, headers);

    expect(what + " is 400 / -32020", response.status() == 400
        && response.body().has("error")
        && response.body().getAsJsonObject("error").get("code").getAsInt() == -32020);
  }

  private void expectCode(McpDispatcher dispatcher, String what, String body, int code) {
    final McpResponse response = dispatcher.handle(body, headers(null, null, null));

    expect(what + " is " + code, response.body() != null
        && response.body().has("error")
        && response.body().getAsJsonObject("error").get("code").getAsInt() == code);
  }

  private void expect(String what, boolean ok) {
    if (this.verbose) {
      System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    if (!ok) {
      this.failures++;
    }
  }

  /** Echoes its arguments, or refuses when told to. Enough to exercise both error channels. */
  private static final class StubTool implements Tool {

    @Override
    public String name() {
      return "lx_stub";
    }

    @Override
    public String title() {
      return "Stub";
    }

    @Override
    public String description() {
      return "Echoes its arguments. Self-check only.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.noArguments();
    }

    @Override
    public boolean readOnly() {
      return true;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      if (arguments.has("fail")) {
        throw new McpToolException("the stub was asked to fail");
      }

      return arguments;
    }
  }
}
