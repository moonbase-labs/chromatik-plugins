package laserphile.chromatik.touch;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import laserphile.chromatik.core.VideoFrame;

import heronarts.lx.LX;

/**
 * Lightweight preview window for calibration: Raw, Processed, or side-by-side.
 */
final class TouchPreviewCanvas {

  static final String[] OPTIONS = { "Off", "Raw", "Proc", "Both" };

  static final int OFF = 0;
  static final int RAW = 1;
  static final int PROCESSED = 2;
  static final int BOTH = 3;
  private static final int DEFAULT_PREVIEW_PORT = 42070;

  private final String title;

  private volatile BufferedImage latestRaw;
  private volatile BufferedImage latestProcessed;

  private HttpServer server;
  private int port = -1;

  private int mode = OFF;
  private volatile boolean announced = false;

  TouchPreviewCanvas(String title) {
    this.title = title;
  }

  void setMode(int mode) {
    this.mode = Math.max(OFF, Math.min(mode, BOTH));

    if (this.mode != OFF) {
      ensureServer();
      announcePreviewUrl();
    }
  }

  void update(VideoFrame rawFrame, VideoFrame processedFrame) {
    if (this.mode == OFF) {
      return;
    }

    this.latestRaw = (rawFrame == null) ? null : toImage(rawFrame);
    this.latestProcessed = (processedFrame == null) ? null : toImage(processedFrame);
  }

  void close() {
    this.mode = OFF;
    if (this.server != null) {
      this.server.stop(0);
      this.server = null;
    }
    this.port = -1;
    this.announced = false;
    this.latestRaw = null;
    this.latestProcessed = null;
  }

  private void ensureServer() {
    if (this.server != null) {
      return;
    }

    try {
      this.server = createServer(DEFAULT_PREVIEW_PORT);
    } catch (IOException preferredPortFailure) {
      try {
        this.server = createServer(0);
      } catch (IOException fallbackFailure) {
        LX.log(String.format(
          "[LaserphileTouch] preview server init failed: preferred=%s fallback=%s",
          preferredPortFailure,
          fallbackFailure));
        this.server = null;
        this.port = -1;
        return;
      }

      LX.log(String.format(
        "[LaserphileTouch] preview port %d unavailable, using %d",
        DEFAULT_PREVIEW_PORT,
        this.port));
    }
  }

  private HttpServer createServer(int requestedPort) throws IOException {
    final HttpServer boundServer = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
    this.port = boundServer.getAddress().getPort();

    boundServer.createContext("/", this::servePage);
    boundServer.createContext("/mode", this::serveMode);
    boundServer.createContext("/raw.png", exchange -> serveImage(exchange, this.latestRaw));
    boundServer.createContext("/processed.png", exchange -> serveImage(exchange, this.latestProcessed));

    boundServer.start();
    return boundServer;
  }

  private void announcePreviewUrl() {
    if (this.announced || this.port < 0) {
      return;
    }

    this.announced = true;
    LX.log(String.format(
      "[LaserphileTouch] %s preview available at http://127.0.0.1:%d/",
      this.title,
      this.port));
  }

  private void servePage(HttpExchange exchange) throws IOException {
    final byte[] body = htmlPage().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private void serveMode(HttpExchange exchange) throws IOException {
    final byte[] body = String.valueOf(this.mode).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private void serveImage(HttpExchange exchange, BufferedImage image) throws IOException {
    if (image == null || this.mode == OFF) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }

    final ByteArrayOutputStream pngBytes = new ByteArrayOutputStream(128 * 1024);
    ImageIO.write(image, "png", pngBytes);
    final byte[] body = pngBytes.toByteArray();

    exchange.getResponseHeaders().set("Content-Type", "image/png");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static String htmlPage() {
    return """
<!doctype html>
<html>
<head>
  <meta charset=\"utf-8\" />
  <title>Laserphile Touch Preview</title>
  <style>
    body { margin: 0; background: #111; color: #ddd; font-family: Segoe UI, sans-serif; }
    .bar { padding: 8px 12px; background: #1f1f1f; border-bottom: 1px solid #333; }
    .row { display: flex; gap: 8px; padding: 8px; box-sizing: border-box; height: calc(100vh - 42px); }
    img { object-fit: contain; background: #000; border: 1px solid #333; width: 100%; height: 100%; }
    .col { flex: 1; min-width: 0; }
    .hidden { display: none; }
  </style>
</head>
<body>
  <div class=\"bar\">Laserphile Touch Preview (Raw / Processed)</div>
  <div class=\"row\">
    <div id=\"rawCol\" class=\"col\"><img id=\"raw\" alt=\"raw\" /></div>
    <div id=\"procCol\" class=\"col\"><img id=\"proc\" alt=\"processed\" /></div>
  </div>
  <script>
    async function tick() {
      const mode = parseInt(await (await fetch('/mode', { cache: 'no-store' })).text(), 10);
      const rawCol = document.getElementById('rawCol');
      const procCol = document.getElementById('procCol');
      const ts = Date.now();

      rawCol.classList.toggle('hidden', !(mode === 1 || mode === 3));
      procCol.classList.toggle('hidden', !(mode === 2 || mode === 3));

      if (mode === 1 || mode === 3) {
        document.getElementById('raw').src = '/raw.png?t=' + ts;
      }
      if (mode === 2 || mode === 3) {
        document.getElementById('proc').src = '/processed.png?t=' + ts;
      }
    }
    setInterval(tick, 120);
    tick();
  </script>
</body>
</html>
""";
  }

  private static BufferedImage toImage(VideoFrame frame) {
    final BufferedImage image =
      new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width);
    return image;
  }
}