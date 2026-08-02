package laserphile.chromatik.mcp;

/**
 * The handful of request headers the protocol gives meaning to.
 *
 * <p>A typed value rather than an {@code HttpExchange} so the dispatcher, and every check written
 * against it, can run with no socket open. {@link McpHttpHandler} is the only thing that builds
 * one from a real request.
 *
 * @param protocolVersion {@code MCP-Protocol-Version}, absent for pre-2025-06-18 clients
 * @param mcpMethod {@code Mcp-Method}, required in the modern era, must equal the body's method
 * @param mcpName {@code Mcp-Name}, required in the modern era for calls that name a target
 * @param origin {@code Origin}, validated against DNS rebinding
 */
record RequestHeaders(String protocolVersion, String mcpMethod, String mcpName, String origin) {

  /** The base64 form the specification defines for values that are not header-safe ASCII. */
  private static final String ENCODED_PREFIX = "=?base64?";
  private static final String ENCODED_SUFFIX = "?=";

  /**
   * {@code mcpName} with the base64 wrapper removed if it has one.
   *
   * <p>Clients must encode a tool name that cannot be represented as plain ASCII, and servers must
   * decode before comparing it to the body. Every tool here is ASCII, so this only ever fires for a
   * name that does not exist, but comparing the raw value would report that as a header mismatch
   * instead of an unknown tool and send the client chasing the wrong problem.
   */
  String decodedMcpName() {
    if (this.mcpName == null
        || !this.mcpName.startsWith(ENCODED_PREFIX)
        || !this.mcpName.endsWith(ENCODED_SUFFIX)) {
      return this.mcpName;
    }

    final String encoded = this.mcpName.substring(
        ENCODED_PREFIX.length(), this.mcpName.length() - ENCODED_SUFFIX.length());

    try {
      return new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return this.mcpName;
    }
  }

  /**
   * Whether this origin may talk to us.
   *
   * <p>The specification requires servers to validate {@code Origin} to stop a web page in the
   * user's browser from reaching a loopback MCP server through DNS rebinding. A missing header is
   * allowed: native clients do not send one, and it is browsers that the check exists to stop.
   */
  boolean hasAllowedOrigin() {
    if (this.origin == null || this.origin.isBlank()) {
      return true;
    }

    return this.origin.startsWith("http://127.0.0.1")
        || this.origin.startsWith("http://localhost")
        || this.origin.startsWith("http://[::1]");
  }
}
