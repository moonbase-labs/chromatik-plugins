package laserphile.chromatik.mcp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The listening socket, and its lifetime.
 *
 * <p>{@code com.sun.net.httpserver} rather than an embedded servlet container, because Chromatik's
 * bundled runtime image includes {@code jdk.httpserver} and every package jar shares one class
 * loader with every other. A container would mean shading several thousand classes into that
 * shared loader for a server that answers a handful of JSON requests.
 */
final class McpHttpServer {

  /** Loopback only. Not configurable, which is the security model rather than a gap in it. */
  private static final String BIND_HOST = "127.0.0.1";

  private static final String CONTEXT_PATH = "/mcp";

  /**
   * How many ports to try, starting at the preferred one.
   *
   * <p>A fixed default is what lets a client be configured once and keep working across restarts,
   * which an ephemeral port does not. The scan is what stops a second Chromatik instance, or an
   * unrelated squatter, from turning that into a failure to start.
   */
  private static final int PORT_SCAN_RANGE = 10;

  /**
   * Four workers. Requests are short and a client makes them one at a time, but a stuck handler
   * should not be able to wedge the next request, and the frame-snapshot tool is slower than the
   * rest.
   */
  private static final int WORKER_THREADS = 4;

  private static final int SHUTDOWN_GRACE_SECONDS = 1;

  private final McpHttpHandler handler;

  private HttpServer server;
  private ExecutorService workers;

  McpHttpServer(McpHttpHandler handler) {
    this.handler = handler;
  }

  /**
   * Bind and start serving.
   *
   * @param preferredPort the first port to try
   * @return the port actually bound
   * @throws IOException if every port in the scan range was taken
   */
  int start(int preferredPort) throws IOException {
    final InetAddress loopback = InetAddress.getByName(BIND_HOST);
    BindException lastFailure = null;

    for (int port = preferredPort; port < preferredPort + PORT_SCAN_RANGE; port++) {
      try {
        this.server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
      } catch (BindException taken) {
        lastFailure = taken;
        continue;
      }

      final AtomicInteger threadNumber = new AtomicInteger(1);
      this.workers = Executors.newFixedThreadPool(WORKER_THREADS, runnable -> {
        final Thread thread = new Thread(runnable, "laserphile-mcp-" + threadNumber.getAndIncrement());

        // Daemon, so a dispose() that never ran cannot hold the JVM open after the window closes.
        thread.setDaemon(true);
        return thread;
      });

      this.server.setExecutor(this.workers);
      this.server.createContext(CONTEXT_PATH, this.handler);
      this.server.start();

      return port;
    }

    throw new IOException(String.format(
        "No free port between %d and %d. Set -Dlaserphile.mcp.port to move the server.",
        preferredPort, preferredPort + PORT_SCAN_RANGE - 1), lastFailure);
  }

  /** Stop serving and release everything. Safe to call twice, and never throws. */
  void stop() {
    if (this.server != null) {
      this.server.stop(SHUTDOWN_GRACE_SECONDS);
      this.server = null;
    }

    // HttpServer.stop does not touch an executor the caller supplied, so without this the worker
    // threads outlive the server. They are daemons, so it would not hold the JVM open, but a
    // plugin disabled and re-enabled in one session would leak a pool each time.
    if (this.workers != null) {
      this.workers.shutdownNow();

      try {
        this.workers.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }

      this.workers = null;
    }
  }
}
