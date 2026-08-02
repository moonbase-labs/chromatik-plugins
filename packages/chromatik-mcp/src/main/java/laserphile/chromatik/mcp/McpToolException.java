package laserphile.chromatik.mcp;

/**
 * A tool could not do what was asked, for a reason the model should read and react to.
 *
 * <p>An unknown parameter path, a channel index past the end of the mixer, a pattern class that is
 * not installed, an engine that is paused. None of these are protocol faults, so none of them
 * become JSON-RPC errors: they come back as an ordinary tool result carrying {@code isError}, which
 * is the form clients hand to the model for self-correction. A JSON-RPC error, by contrast, usually
 * surfaces as a client-side failure the model never sees.
 *
 * <p>The message is written for the model. One sentence, no stack trace, no absolute paths, and
 * where possible it names what to do instead.
 */
final class McpToolException extends Exception {

  private static final long serialVersionUID = 1L;

  McpToolException(String message) {
    super(message);
  }
}
