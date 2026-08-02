package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import heronarts.lx.LX;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/**
 * Where the server tells the outside world how to reach it.
 *
 * <p>Written to {@code ~/Chromatik/LaserphileMCP/server.json} on start and deleted on stop. A
 * client needs the port before it can connect, and the port is only known at runtime once the scan
 * has found a free one, so something has to publish it. A file in a predictable place is the
 * smallest thing that works and stays readable by a human debugging a connection.
 *
 * <p>The process id is included so a file left behind by a crash is recognisable as stale rather
 * than being trusted and producing a confusing connection refused.
 */
final class ServerStatusFile {

  private static final String RELATIVE_PATH = "LaserphileMCP/server.json";

  private final File file;

  /**
   * Removes the file if the process ends without {@code dispose()} having run.
   *
   * <p>An orderly quit disposes the plugin and this is never needed. A signal does not:
   * {@code kill} on a headless run leaves the file behind pointing at a port nothing is serving,
   * and the next client to read it gets a connection refused rather than a clear "not running".
   * Held as a field so it can be deregistered, otherwise a plugin toggled off and on in one
   * session would accumulate hooks.
   */
  private Thread shutdownHook;

  ServerStatusFile(LX lx) {
    this.file = lx.getMediaFile(RELATIVE_PATH);
  }

  /** Publish the endpoint. Failure is logged, never thrown: the server itself is already up. */
  void write(int port, String serverVersion, String lxVersion) {
    final JsonArray versions = new JsonArray();

    for (String version : ProtocolEra.supportedVersions()) {
      versions.add(version);
    }

    final JsonObject status = new JsonObject();
    status.addProperty("url", url(port));
    status.addProperty("host", "127.0.0.1");
    status.addProperty("port", port);
    status.add("protocolVersions", versions);
    status.addProperty("serverVersion", serverVersion);
    status.addProperty("lxVersion", lxVersion);
    status.addProperty("pid", ProcessHandle.current().pid());
    status.addProperty("startedAt", Instant.now().toString());

    try {
      final File parent = this.file.getParentFile();

      if (parent != null) {
        parent.mkdirs();
      }

      Files.writeString(this.file.toPath(), JsonRpc.GSON.toJson(status) + "\n", StandardCharsets.UTF_8);
      registerShutdownHook();
    } catch (IOException | SecurityException failed) {
      LX.error(failed, "[LaserphileMCP] could not write " + RELATIVE_PATH + "; connect using the port in the log instead");
    }
  }

  void delete() {
    deregisterShutdownHook();
    deleteQuietly();
  }

  private void registerShutdownHook() {
    if (this.shutdownHook != null) {
      return;
    }

    this.shutdownHook = new Thread(this::deleteQuietly, "laserphile-mcp-status-cleanup");

    try {
      Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    } catch (IllegalStateException alreadyShuttingDown) {
      this.shutdownHook = null;
    }
  }

  private void deregisterShutdownHook() {
    if (this.shutdownHook == null) {
      return;
    }

    try {
      Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
    } catch (IllegalStateException alreadyShuttingDown) {
      // The hook is running right now, which does the same job. Nothing to do.
    }

    this.shutdownHook = null;
  }

  /** Delete without logging through LX, which may already be torn down when a hook runs. */
  private void deleteQuietly() {
    try {
      Files.deleteIfExists(this.file.toPath());
    } catch (IOException | SecurityException failed) {
      System.err.println("[LaserphileMCP] could not remove " + RELATIVE_PATH + ": " + failed.getMessage());
    }
  }

  static String url(int port) {
    return "http://127.0.0.1:" + port + "/mcp";
  }
}
