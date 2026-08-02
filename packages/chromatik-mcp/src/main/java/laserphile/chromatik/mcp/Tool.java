package laserphile.chromatik.mcp;

import com.google.gson.JsonObject;

/**
 * One thing an agent can ask Chromatik to do.
 *
 * <p>A tool knows nothing about HTTP, JSON-RPC or protocol eras. It takes an arguments object and
 * returns the JSON that describes what it found or what it changed. Everything else is the
 * dispatcher's problem, which is what keeps the tool layer testable without a socket.
 */
interface Tool {

  /** Stable identifier the client calls. Lowercase, underscore-separated, prefixed {@code lx_}. */
  String name();

  /** Human-readable name for a client's tool picker. */
  String title();

  /**
   * What this does, written for the model rather than for a developer.
   *
   * <p>This is the only steering the model gets before it decides whether to call, so it should say
   * what the tool answers, what shape comes back, and anything non-obvious about when to reach for
   * it. It is also charged to the context window on every turn, so it should be tight.
   */
  String description();

  /** JSON Schema for the arguments. Must be an object schema, never null. */
  JsonObject inputSchema();

  /** Whether this only reads. Advertised to clients so they can skip confirming safe calls. */
  boolean readOnly();

  /**
   * Do the work.
   *
   * @param arguments the client's {@code arguments} object, never null but possibly empty
   * @return the JSON payload to hand back, which becomes the tool result's content
   * @throws McpToolException when the request was well formed but could not be satisfied; the
   *     message goes to the model as an error result it can react to
   */
  JsonObject call(JsonObject arguments) throws McpToolException;
}
