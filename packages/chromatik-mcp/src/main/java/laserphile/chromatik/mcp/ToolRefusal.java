package laserphile.chromatik.mcp;

/**
 * A refusal raised from inside an engine task, where a checked exception cannot be declared.
 *
 * <p>{@link EngineBridge} takes a {@code Supplier}, so a task body cannot throw
 * {@link McpToolException} directly. Without this, every ordinary agent mistake, a path that does
 * not resolve, a class name that is not installed, would arrive at the bridge as an unexpected
 * throwable: logged with a full stack trace and reported to the model as an internal failure. Both
 * are wrong. A wrong path is the model's to fix and says so in one sentence, and the log should be
 * kept for things that are actually broken.
 *
 * <p>Unchecked so it passes through the supplier; unwrapped by the bridge into the checked form
 * without logging.
 */
final class ToolRefusal extends RuntimeException {

  private static final long serialVersionUID = 1L;

  ToolRefusal(String message) {
    // No stack trace: this is a message, not a fault, and filling one in is pure cost.
    super(message, null, false, false);
  }
}
