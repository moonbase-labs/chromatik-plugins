package laserphile.chromatik.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import heronarts.lx.LX;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The only class that knows this server speaks HTTP.
 *
 * <p>Everything above it deals in strings and records. That boundary is what makes the protocol
 * checkable without binding a port, and it is also why the transport rules live here rather than
 * being scattered: what verbs are allowed, which origins may connect, how large a body may be.
 */
final class McpHttpHandler implements HttpHandler {

  /**
   * Largest request body accepted.
   *
   * <p>Nothing legitimate comes close: the biggest thing a client sends is a batch of parameter
   * sets. The cap is here so an unauthenticated loopback port cannot be made to allocate without
   * bound by anything that can open a socket.
   */
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  private final McpDispatcher dispatcher;

  McpHttpHandler(McpDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      route(exchange);
    } catch (Throwable failure) {
      // Nothing may escape into the HTTP server's worker: it would be logged somewhere nobody
      // looks and the client would see a dropped connection rather than an answer.
      LX.error(failure, "[LaserphileMCP] unhandled error serving a request");
      respond(exchange, 500, JsonRpc.GSON.toJson(
          JsonRpc.error(null, JsonRpc.INTERNAL_ERROR, "Internal error", null)));
    } finally {
      exchange.close();
    }
  }

  private void route(HttpExchange exchange) throws IOException {
    final RequestHeaders headers = new RequestHeaders(
        exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"),
        exchange.getRequestHeaders().getFirst("Mcp-Method"),
        exchange.getRequestHeaders().getFirst("Mcp-Name"),
        exchange.getRequestHeaders().getFirst("Origin"));

    if (!headers.hasAllowedOrigin()) {
      respond(exchange, 403, "");
      return;
    }

    // GET opened the standalone notification stream in older revisions and DELETE ended a session.
    // Neither exists now, and the specification asks a modern-only endpoint to say so plainly
    // rather than failing in a way an old client would misread.
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.getResponseHeaders().add("Allow", "POST");
      respond(exchange, 405, "");
      return;
    }

    final String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

    if (contentType != null && !contentType.toLowerCase().startsWith("application/json")) {
      respond(exchange, 415, "");
      return;
    }

    final String body = readBody(exchange);

    if (body == null) {
      respond(exchange, 413, "");
      return;
    }

    final McpResponse response = this.dispatcher.handle(body, headers);
    respond(exchange, response.status(), response.serialize());
  }

  /** The request body, or null if it exceeded {@link #MAX_BODY_BYTES}. */
  private String readBody(HttpExchange exchange) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      // One byte past the cap, so a body exactly at the limit still reads and anything larger is
      // detectable without having buffered all of it.
      final byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);

      return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

    if (bytes.length > 0) {
      exchange.getResponseHeaders().add("Content-Type", "application/json");
    }

    // -1 means no body at all, which is what 202, 403, 405, 413 and 415 send here. Passing 0
    // instead would promise chunked content that never arrives and hang the client.
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);

    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
