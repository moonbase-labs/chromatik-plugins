package laserphile.chromatik.mcp;

import com.google.gson.JsonElement;

/**
 * A protocol-level failure: the request itself was wrong, in a way the model could have avoided.
 *
 * <p>This is the other half of a deliberate split. A request that is malformed, names a method or
 * tool that does not exist, or contradicts its own headers becomes one of these and travels as a
 * JSON-RPC {@code error}. A request that was well formed but could not be satisfied by the current
 * state of Chromatik becomes an {@link McpToolException} and travels as a normal result with
 * {@code isError}, because that is the form a client passes back to the model to reason about.
 */
final class JsonRpcException extends Exception {

  private static final long serialVersionUID = 1L;

  final int code;
  final transient JsonElement data;

  JsonRpcException(int code, String message) {
    this(code, message, null);
  }

  JsonRpcException(int code, String message, JsonElement data) {
    super(message);
    this.code = code;
    this.data = data;
  }
}
