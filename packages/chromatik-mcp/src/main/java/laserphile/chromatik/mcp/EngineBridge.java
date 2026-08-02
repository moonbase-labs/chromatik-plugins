package laserphile.chromatik.mcp;

import com.google.gson.JsonObject;
import heronarts.lx.LX;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The seam between the HTTP worker threads and Chromatik's engine thread.
 *
 * <p>{@code lx.engine.addTask} is the only sanctioned way to touch engine state from another
 * thread: the queue is a synchronized list, drained at the top of the next engine loop. Everything
 * a tool does goes through here, reads included, because the mixer's channel list and the
 * registry's class lists are plain {@code ArrayList}s mutated on the engine thread, and iterating
 * one from an HTTP worker is a {@code ConcurrentModificationException} waiting for a busy moment.
 *
 * <p>The supplier builds its JSON <em>on the engine thread</em> and returns it. Nothing that is an
 * {@code LXComponent}, an {@code LXParameter}, or a live collection may escape, and typing the
 * supplier as {@link JsonObject} rather than something generic is what makes the compiler enforce
 * that.
 *
 * <p>Three properties of {@code LXEngine} in 1.2.1 shape the rest of this class, all confirmed by
 * disassembly rather than documentation:
 *
 * <ul>
 *   <li>{@code run(boolean)} wraps the loop in {@code catch (Throwable)}, and on any throwable sets
 *       a permanent {@code runFailed} flag and calls {@code LX.fail}. The task drain happens inside
 *       that try. So a throwable escaping a queued task does not fail one request, it stops
 *       Chromatik rendering for the rest of the session. Hence the {@code catch (Throwable)} below,
 *       which is the single most important line in this package.
 *   <li>The loop returns early when paused, <em>before</em> the drain, so a paused engine silently
 *       strands every queued task rather than running them late.
 *   <li>A cancelled {@code CompletableFuture} does not cancel a queued {@code Runnable}. Without
 *       the claim below, a write that timed out would still be applied, seconds later, by which
 *       time the agent has moved on and reasoned about a state that never existed.
 * </ul>
 */
final class EngineBridge {

  /**
   * How long an HTTP worker waits for the engine to pick up a task.
   *
   * <p>Three frames at 60fps is 50ms, so this is generous by two orders of magnitude. It is set
   * where it is to absorb a slow frame during a heavy render and still fail well inside any
   * client's own timeout.
   */
  private static final long TASK_TIMEOUT_MS = 2000;

  /** Once the engine has claimed a task, how much longer it gets to finish. */
  private static final long CLAIM_GRACE_MS = 500;

  /** No engine frame in this long means stopped, paused, or not started yet. */
  private static final long ENGINE_STALE_MS = 1000;

  private final LX lx;

  /** Written every frame by the heartbeat, read by every HTTP worker. */
  private volatile long lastFrameMs = 0;

  /** Captured by the heartbeat, so {@link #call} can refuse to deadlock on itself. */
  private volatile Thread engineThread = null;

  EngineBridge(LX lx) {
    this.lx = lx;

    // One volatile write per frame. A loop task is skipped for exactly the same reasons a queued
    // task is, so a stale heartbeat is a precise signal that posting would be pointless.
    this.lx.engine.addLoopTask(deltaMs -> {
      this.lastFrameMs = System.currentTimeMillis();
      this.engineThread = Thread.currentThread();
    });
  }

  /**
   * Run {@code body} on the engine thread and return what it built.
   *
   * @param what a short description of the work, used in any failure message the model reads
   * @throws McpToolException if the engine is not running, did not get to it in time, or the body
   *     itself refused
   */
  JsonObject call(String what, Supplier<JsonObject> body) throws McpToolException {
    if (Thread.currentThread() == this.engineThread) {
      // Structurally impossible today, since com.sun.net.httpserver runs handlers on its own
      // executor. Checked anyway, because if it ever becomes possible the symptom is a two-second
      // hang per request rather than anything that names itself.
      throw new McpToolException("internal error: " + what + " ran on the engine thread");
    }

    requireRunningEngine(what);

    final CompletableFuture<JsonObject> result = new CompletableFuture<>();

    // Whoever wins this decides whether the work happens at all. It is what makes a reported
    // timeout mean "definitely not applied" rather than "applied at some point, possibly later".
    final AtomicBoolean claimed = new AtomicBoolean(false);

    this.lx.engine.addTask(() -> {
      if (!claimed.compareAndSet(false, true)) {
        return;
      }

      try {
        if (this.lx.isLoading()) {
          throw new McpToolException("Chromatik is loading a project, try again in a moment");
        }

        result.complete(body.get());
      } catch (Throwable failure) {
        // Must not escape. See the class comment: LXEngine treats a throwable out of its loop as
        // fatal and stops rendering permanently.
        result.completeExceptionally(failure);
      }
    });

    try {
      return result.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    } catch (TimeoutException tooSlow) {
      if (!claimed.compareAndSet(false, true)) {
        // The engine claimed it between the timeout firing and this check, so it is running now.
        // Give it a bounded moment rather than reporting something untrue.
        return awaitClaimed(what, result);
      }

      throw new McpToolException(String.format(
          "the Chromatik engine did not run '%s' within %d ms, so it was abandoned and nothing was changed",
          what, TASK_TIMEOUT_MS));

    } catch (ExecutionException failed) {
      throw asToolException(what, failed.getCause());

    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new McpToolException("interrupted while waiting for '" + what + "'");
    }
  }

  /** Whether the engine has produced a frame recently enough to be worth posting work to. */
  boolean isEngineRunning() {
    return this.lastFrameMs != 0 && (System.currentTimeMillis() - this.lastFrameMs) < ENGINE_STALE_MS;
  }

  /**
   * Fail immediately, and specifically, when the engine cannot run anything.
   *
   * <p>Distinguishing "not started" from "stopped or paused" costs one comparison and saves the
   * agent from retrying something that will never succeed until a human clicks play. Failing in
   * under a millisecond also beats hanging for two seconds per request for as long as the user
   * leaves Chromatik paused.
   */
  private void requireRunningEngine(String what) throws McpToolException {
    if (this.lastFrameMs == 0) {
      throw new McpToolException(
          "the Chromatik engine has not started yet, so '" + what + "' cannot run");
    }

    final long sinceFrame = System.currentTimeMillis() - this.lastFrameMs;

    if (sinceFrame >= ENGINE_STALE_MS) {
      throw new McpToolException(String.format(
          "the Chromatik engine is not running (no frame in %d ms), it is probably paused", sinceFrame));
    }
  }

  private JsonObject awaitClaimed(String what, CompletableFuture<JsonObject> result)
      throws McpToolException {
    try {
      return result.get(CLAIM_GRACE_MS, TimeUnit.MILLISECONDS);

    } catch (TimeoutException stillRunning) {
      throw new McpToolException(
          "'" + what + "' started but did not finish in time; it may still be applied");

    } catch (ExecutionException failed) {
      throw asToolException(what, failed.getCause());

    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new McpToolException("interrupted while waiting for '" + what + "'");
    }
  }

  /**
   * Turn whatever came out of the task into something the model can read.
   *
   * <p>A tool's own refusal is already written for that audience and passes through. Anything else
   * is a bug here: it goes to the log in full, and to the wire as one sentence, because a stack
   * trace burns the model's context and leaks local paths without helping it recover.
   */
  private McpToolException asToolException(String what, Throwable cause) {
    if (cause instanceof McpToolException refused) {
      return refused;
    }

    // A refusal raised inside the task. Already written for the model, and not a fault, so it
    // passes through verbatim and nothing is logged.
    if (cause instanceof ToolRefusal refusal) {
      return new McpToolException(refusal.getMessage());
    }

    LX.error(cause, "[LaserphileMCP] " + what + " failed");

    final String detail = cause == null || cause.getMessage() == null
        ? cause == null ? "unknown error" : cause.getClass().getSimpleName()
        : cause.getMessage();

    return new McpToolException("'" + what + "' failed inside Chromatik: " + detail);
  }
}
