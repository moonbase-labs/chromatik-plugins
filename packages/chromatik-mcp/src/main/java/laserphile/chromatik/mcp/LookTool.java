package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import heronarts.lx.LX;
import heronarts.lx.LXEngine;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Lets an agent see what the rig is actually showing.
 *
 * <p>Chromatik renders onto a point cloud, and there is no screenshot API for its 3D preview
 * anywhere in the three jars it ships. That turns out not to matter, because projecting the point
 * cloud ourselves is the better signal anyway: it is headless-safe, deterministic, and shows what
 * the engine computed rather than wherever the user happens to have left the preview camera.
 *
 * <p>An image is also the cheapest useful thing to send. A 320x320 PNG costs roughly 137 tokens,
 * which is less than a forty-line JSON summary of the same frame and carries far more. Both go
 * back: the picture is what the model reasons about now, and the statistics are what survive in
 * context after a client prunes old images, and what it can compare numerically between calls.
 */
final class LookTool implements Tool {

  private static final int DEFAULT_SIZE = 320;
  private static final int MAX_FRAMES = 4;
  private static final int MAX_INTERVAL_MS = 500;

  /**
   * Not black. An unlit LED and a place with no LEDs at all are the two states an agent most needs
   * to tell apart, and on a black background they look identical.
   */
  private static final int BACKGROUND = 0x202020;

  private final LX lx;

  /** Previous frame's colours, for the change measure. Only ever touched on an HTTP worker. */
  private int[] previousColors = null;

  LookTool(LX lx) {
    this.lx = lx;
  }

  @Override
  public String name() {
    return "lx_look";
  }

  @Override
  public String title() {
    return "Look at the output";
  }

  @Override
  public String description() {
    return "Renders what the rig is currently showing as a PNG, plus brightness and coverage "
        + "statistics. Call after any visual change. Set frames=2..4 to get a contact sheet across "
        + "a short span, which is the only way to see whether something is animating.";
  }

  @Override
  public JsonObject inputSchema() {
    return Schema.object(
        "view", Schema.choice("Projection. \"auto\" picks unwrap for a 3D shell and front for a "
            + "flat model.", "auto", "front", "top", "side", "unwrap"),
        "size", Schema.integer("Image edge in pixels.", 128, 512, DEFAULT_SIZE),
        "frames", Schema.integer("Snapshots to tile into one image, to reveal motion.", 1, MAX_FRAMES, 1),
        "intervalMs", Schema.integer("Gap between snapshots.", 50, MAX_INTERVAL_MS, 300));
  }

  @Override
  public boolean readOnly() {
    return true;
  }

  @Override
  public JsonObject call(JsonObject arguments) throws McpToolException {
    final String requestedView = Args.optionalString(arguments, "view", "auto");
    final int size = clamp(Args.optionalInt(arguments, "size", DEFAULT_SIZE), 128, 512);
    final int frames = clamp(Args.optionalInt(arguments, "frames", 1), 1, MAX_FRAMES);
    final int intervalMs = clamp(Args.optionalInt(arguments, "intervalMs", 300), 50, MAX_INTERVAL_MS);

    final LXModel model = this.lx.getModel();

    if (model == null || model.size == 0) {
      throw new McpToolException("the model has no points, so there is nothing to look at");
    }

    final String view = "auto".equals(requestedView) ? autoView(model) : requestedView;

    final BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    fill(canvas, BACKGROUND);

    // Tiled left to right, top to bottom, so a contact sheet reads in time order.
    final int columns = frames > 1 ? 2 : 1;
    final int cell = frames > 1 ? (size - 1) / columns : size;

    int[] lastColors = null;

    for (int frame = 0; frame < frames; frame++) {
      if (frame > 0) {
        pause(intervalMs);
      }

      lastColors = snapshot(model);

      final int column = frame % columns;
      final int row = frame / columns;

      splat(canvas, model, lastColors, view, column * (cell + 1), row * (cell + 1), cell);
    }

    final JsonObject result = statistics(model, lastColors);
    result.addProperty("view", view);
    result.addProperty("frames", frames);

    if (frames > 1) {
      result.addProperty("spanMs", (frames - 1) * intervalMs);
    }

    result.addProperty(IMAGE_MEMBER, encode(canvas));

    this.previousColors = lastColors;

    return result;
  }

  /**
   * Take a copy of the current frame.
   *
   * <p>Deliberately not routed through {@link EngineBridge}. {@code copyFrameThreadSafe} exists to
   * be called from another thread and takes the double buffer's lock itself, so posting it as an
   * engine task would run it on the one thread it must not.
   */
  private int[] snapshot(LXModel model) throws McpToolException {
    try {
      final LXEngine.Frame frame = new LXEngine.Frame(this.lx);
      this.lx.engine.copyFrameThreadSafe(frame);

      final int[] colors = frame.getColors();

      // The model can change between constructing the frame and filling it. Rare, and cheaper to
      // notice than to prevent.
      if (colors == null || colors.length < model.size) {
        throw new McpToolException("the model changed while reading the frame, try again");
      }

      return colors;
    } catch (McpToolException refused) {
      throw refused;
    } catch (RuntimeException failed) {
      throw new McpToolException("could not read the current frame: " + failed.getMessage());
    }
  }

  /**
   * A flat model is best seen face on; a shell needs unwrapping or the far side hides the near.
   *
   * <p>This is why {@code lx_project} reports {@code planar}: the same judgement, made once.
   */
  private String autoView(LXModel model) {
    float minX = Float.MAX_VALUE;
    float maxX = -Float.MAX_VALUE;
    float minY = Float.MAX_VALUE;
    float maxY = -Float.MAX_VALUE;
    float minZ = Float.MAX_VALUE;
    float maxZ = -Float.MAX_VALUE;

    for (LXPoint point : model.points) {
      minX = Math.min(minX, point.x);
      maxX = Math.max(maxX, point.x);
      minY = Math.min(minY, point.y);
      maxY = Math.max(maxY, point.y);
      minZ = Math.min(minZ, point.z);
      maxZ = Math.max(maxZ, point.z);
    }

    final float depth = maxZ - minZ;
    final float widest = Math.max(maxX - minX, maxY - minY);

    return depth <= widest * 0.02f ? "front" : "unwrap";
  }

  /**
   * Draw every point into one cell of the canvas.
   *
   * <p>Points are splatted as small discs and blended by taking the brighter of the two, so a
   * sparse cloud reads as a lit surface rather than as confetti, and an overlap never dims what is
   * underneath.
   */
  private void splat(BufferedImage canvas, LXModel model, int[] colors,
      String view, int originX, int originY, int cell) {

    final int radius = Math.max(1, Math.round(0.6f * cell / (float) Math.sqrt(model.size)));

    for (int index = 0; index < model.size; index++) {
      final LXPoint point = model.points[index];
      final float[] uv = project(point, view);

      final int centreX = originX + Math.round(uv[0] * (cell - 1));
      final int centreY = originY + Math.round(uv[1] * (cell - 1));
      final int color = colors[index] & 0xFFFFFF;

      for (int offsetY = -radius; offsetY <= radius; offsetY++) {
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
          if (offsetX * offsetX + offsetY * offsetY > radius * radius) {
            continue;
          }

          final int x = centreX + offsetX;
          final int y = centreY + offsetY;

          if (x < originX || y < originY || x >= originX + cell || y >= originY + cell
              || x >= canvas.getWidth() || y >= canvas.getHeight()) {
            continue;
          }

          canvas.setRGB(x, y, brighter(canvas.getRGB(x, y) & 0xFFFFFF, color));
        }
      }
    }
  }

  /**
   * Where a point lands, in 0..1, with v already flipped so that up is up.
   *
   * <p>{@code unwrap} is equirectangular about the model's centre. It is the only view that shows
   * every face of a dome at once, which for a geodesic rig is the difference between seeing the
   * pattern and seeing the nearest twenty LEDs.
   */
  private float[] project(LXPoint point, String view) {
    return switch (view) {
      case "top" -> new float[] { clamp01(point.xn), clamp01(point.zn) };
      case "side" -> new float[] { clamp01(point.zn), 1 - clamp01(point.yn) };
      case "unwrap" -> {
        final double azimuth = Math.atan2(point.zn - 0.5, point.xn - 0.5);
        yield new float[] {
            (float) (azimuth / (2 * Math.PI) + 0.5),
            1 - clamp01(point.yn)
        };
      }
      default -> new float[] { clamp01(point.xn), 1 - clamp01(point.yn) };
    };
  }

  /** Per-channel maximum, so overlapping splats never darken each other. */
  private int brighter(int existing, int candidate) {
    final int red = Math.max((existing >> 16) & 0xFF, (candidate >> 16) & 0xFF);
    final int green = Math.max((existing >> 8) & 0xFF, (candidate >> 8) & 0xFF);
    final int blue = Math.max(existing & 0xFF, candidate & 0xFF);

    return (red << 16) | (green << 8) | blue;
  }

  /**
   * The numbers that outlive the picture.
   *
   * <p>{@code change} is the one worth having: mean per-point difference against the previous look,
   * which answers "is anything actually animating" without needing a second image or a human eye.
   */
  private JsonObject statistics(LXModel model, int[] colors) {
    long totalRed = 0;
    long totalGreen = 0;
    long totalBlue = 0;
    int lit = 0;
    int clipped = 0;
    int peak = 0;

    for (int index = 0; index < model.size; index++) {
      final int color = colors[index];
      final int red = (color >> 16) & 0xFF;
      final int green = (color >> 8) & 0xFF;
      final int blue = color & 0xFF;
      final int brightest = Math.max(red, Math.max(green, blue));

      totalRed += red;
      totalGreen += green;
      totalBlue += blue;
      peak = Math.max(peak, brightest);

      if (brightest > 8) {
        lit++;
      }

      if (brightest == 255) {
        clipped++;
      }
    }

    final JsonObject stats = new JsonObject();
    stats.addProperty("points", model.size);
    stats.addProperty("lit", lit);
    stats.addProperty("coverage", Args.tidy(lit / (double) model.size));

    final JsonArray mean = new JsonArray();
    mean.add(Math.round(totalRed / (float) model.size));
    mean.add(Math.round(totalGreen / (float) model.size));
    mean.add(Math.round(totalBlue / (float) model.size));
    stats.add("meanRGB", mean);

    stats.addProperty("peak", peak);
    stats.addProperty("clipped", clipped);
    stats.add("regions", regions(model, colors));

    if (this.previousColors != null && this.previousColors.length == colors.length) {
      stats.addProperty("change", Args.tidy(meanDifference(this.previousColors, colors)));
    }

    return stats;
  }

  /** Mean brightness in horizontal thirds, which is enough to spot an upside-down projection. */
  private JsonObject regions(LXModel model, int[] colors) {
    final long[] totals = new long[3];
    final int[] counts = new int[3];

    for (int index = 0; index < model.size; index++) {
      final LXPoint point = model.points[index];
      final int band = Math.min(2, Math.max(0, (int) (clamp01(point.yn) * 3)));
      final int color = colors[index];

      totals[band] += Math.max((color >> 16) & 0xFF, Math.max((color >> 8) & 0xFF, color & 0xFF));
      counts[band]++;
    }

    final JsonObject described = new JsonObject();
    described.addProperty("bottom", counts[0] == 0 ? 0 : Math.round(totals[0] / (float) counts[0]));
    described.addProperty("middle", counts[1] == 0 ? 0 : Math.round(totals[1] / (float) counts[1]));
    described.addProperty("top", counts[2] == 0 ? 0 : Math.round(totals[2] / (float) counts[2]));

    return described;
  }

  private double meanDifference(int[] before, int[] after) {
    long total = 0;

    for (int index = 0; index < after.length; index++) {
      final int a = before[index];
      final int b = after[index];

      total += Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
          + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
          + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    return total / (after.length * 3.0 * 255.0);
  }

  private String encode(BufferedImage canvas) throws McpToolException {
    try {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ImageIO.write(canvas, "png", bytes);

      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (IOException failed) {
      throw new McpToolException("could not encode the frame as a PNG: " + failed.getMessage());
    }
  }

  private void fill(BufferedImage canvas, int color) {
    for (int y = 0; y < canvas.getHeight(); y++) {
      for (int x = 0; x < canvas.getWidth(); x++) {
        canvas.setRGB(x, y, color);
      }
    }
  }

  private void pause(int millis) throws McpToolException {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new McpToolException("interrupted while sampling frames");
    }
  }

  private static float clamp01(float value) {
    return Math.max(0, Math.min(1, value));
  }

  private static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(high, value));
  }
}
