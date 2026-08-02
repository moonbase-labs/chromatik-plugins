package laserphile.chromatik.mcp;

import heronarts.lx.LX;
import heronarts.lx.LXPlugin;
import java.io.IOException;

/**
 * Runs an MCP server inside Chromatik, so an AI agent can read and compose the show.
 *
 * <p>Chromatik's only built-in remote surface is OSC, which can set any parameter that already
 * exists but cannot add a channel, add a pattern, or say what is installed; its OSCQuery companion
 * is read-only and does not enumerate patterns at all. An agent limited to that can operate a show
 * somebody else built but cannot build one. Running in the same process lifts that limit, and
 * routing every change through {@code LXCommand} means the agent's work lands in Chromatik's own
 * undo history rather than beside it.
 *
 * <p>Unlike the rendering packages in this repo, installing the jar is not enough: a plugin has to
 * be ticked in Preferences and Chromatik restarted before it runs.
 *
 * <p>This is an {@link LXPlugin} rather than an {@code LXStudio.Plugin} because it needs no UI, and
 * staying in the UI-free half means it also runs under {@code --headless}. Both kinds require a
 * licence of some sort, but only the absent one is refused: {@code canRunPlugins()} is true on
 * every tier including FREE.
 */
@LXPlugin.Name("Chromatik MCP")
public class ChromatikMcpPlugin implements LXPlugin {

  /**
   * Where the server listens unless told otherwise.
   *
   * <p>Fixed rather than ephemeral so a client can be configured once and keep working across
   * restarts. {@link McpHttpServer} scans upward from here if it is taken.
   */
  private static final int DEFAULT_PORT = 3579;

  private static final String PORT_PROPERTY = "laserphile.mcp.port";
  private static final String PORT_ENVIRONMENT = "LASERPHILE_MCP_PORT";

  private final LX lx;

  private McpHttpServer server;
  private ServerStatusFile statusFile;

  public ChromatikMcpPlugin(LX lx) {
    this.lx = lx;
  }

  @Override
  public void initialize(LX lx) {
    final EngineBridge bridge = new EngineBridge(lx);
    final ParameterCatalog catalog = new ParameterCatalog();

    // Read tools first. The order a client sees is a weak steer, and an agent that orients before
    // it mutates makes better decisions and fewer undo calls.
    final ToolRegistry registry = new ToolRegistry();
    ReadTools.register(registry, lx, bridge, catalog);
    WriteTools.register(registry, lx, bridge, catalog);
    registry.add(new LookTool(lx));

    final McpDispatcher dispatcher = new McpDispatcher(registry, version());
    this.server = new McpHttpServer(new McpHttpHandler(dispatcher));

    final int port;

    try {
      port = this.server.start(preferredPort());
    } catch (IOException couldNotBind) {
      this.server = null;

      // Thrown rather than logged. LXRegistry catches this, records it against the plugin and
      // shows it in the plugin manager, which is somewhere the user will actually see it; a log
      // line about a server that silently is not running is not.
      throw new IllegalStateException(couldNotBind.getMessage(), couldNotBind);
    }

    this.statusFile = new ServerStatusFile(lx);
    this.statusFile.write(port, version(), LX.VERSION);

    LX.log("[LaserphileMCP] listening on " + ServerStatusFile.url(port)
        + " (" + registry.size() + " tools)");
    LX.log("[LaserphileMCP] connect with: claude mcp add --transport http chromatik "
        + ServerStatusFile.url(port));
  }

  @Override
  public void dispose() {
    // Runs inside LXEngine.dispose(), so anything thrown here breaks the rest of Chromatik's
    // shutdown. Nothing in this method is worth that.
    try {
      if (this.server != null) {
        this.server.stop();
        this.server = null;
      }

      if (this.statusFile != null) {
        this.statusFile.delete();
        this.statusFile = null;
      }

      LX.log("[LaserphileMCP] stopped");
    } catch (Throwable failure) {
      LX.error(failure, "[LaserphileMCP] error during shutdown");
    }
  }

  /** System property, then environment variable, then the default. */
  private int preferredPort() {
    final int fromProperty = parsePort(System.getProperty(PORT_PROPERTY), PORT_PROPERTY);

    if (fromProperty > 0) {
      return fromProperty;
    }

    final int fromEnvironment = parsePort(System.getenv(PORT_ENVIRONMENT), PORT_ENVIRONMENT);

    return fromEnvironment > 0 ? fromEnvironment : DEFAULT_PORT;
  }

  /** The port in {@code raw}, or 0 if it is absent or unusable. A bad value warns and falls back. */
  private int parsePort(String raw, String source) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }

    try {
      final int port = Integer.parseInt(raw.trim());

      if (port < 1 || port > 65535) {
        LX.error("[LaserphileMCP] " + source + " is " + raw + ", outside 1-65535; using " + DEFAULT_PORT);
        return 0;
      }

      return port;
    } catch (NumberFormatException notANumber) {
      LX.error("[LaserphileMCP] " + source + " is " + raw + ", which is not a number; using " + DEFAULT_PORT);
      return 0;
    }
  }

  /**
   * This package's version, read from the jar manifest, falling back to a marker when running
   * from a class directory during development.
   */
  private String version() {
    final String implementation = getClass().getPackage().getImplementationVersion();

    return implementation != null ? implementation : "dev";
  }
}
