package laserphile.chromatik.video;

import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;

/**
 * Maps a decoded frame onto the model using a {@link ProjectionParams} snapshot.
 *
 * Each point's normalised (xn, yn, zn) is translated, inverse-rotated into the image plane,
 * scaled/stretched and scrolled to a texture coordinate (u, v), then wrapped and sampled.
 * This is source-agnostic: any future FrameSource reuses it unchanged.
 */
final class Projector {

  void project(VideoFrame frame, ProjectionParams params, LXModel model, int[] colors) {
    final int[] argb = frame.argb;
    final int width = frame.width;
    final int height = frame.height;

    final int background =
      params.backgroundMode == ProjectionParams.BackgroundMode.CLEAR ? 0x00000000 : 0xFF000000;

    for (LXPoint point : model.points) {
      final double centeredX = point.xn - 0.5 - params.translateX;
      final double centeredY = point.yn - 0.5 - params.translateY;
      final double centeredZ = point.zn - 0.5 - params.translateZ;

      // Inverse rotation (R-transpose * centered). z is dropped: orthographic projection.
      final double rotatedX =
        params.m00 * centeredX + params.m10 * centeredY + params.m20 * centeredZ;
      final double rotatedY =
        params.m01 * centeredX + params.m11 * centeredY + params.m21 * centeredZ;

      final double u = rotatedX * params.invScaleX + 0.5 + params.scrollX;
      final double v = rotatedY * params.invScaleY + 0.5 + params.scrollY;

      final int sampled = sampleWrapped(argb, width, height, u, v, params, background);

      colors[point.index] = applyLevel(sampled, params.level);
    }
  }

  /** Master brightness: scales the colour channels and leaves alpha (the CLEAR mode) alone. */
  private static int applyLevel(int argb, double level) {
    if (level >= 1) {
      return argb;
    }

    final int alpha = argb & 0xff000000;
    final int red = (int) (((argb >> 16) & 0xff) * level);
    final int green = (int) (((argb >> 8) & 0xff) * level);
    final int blue = (int) ((argb & 0xff) * level);

    return alpha | (red << 16) | (green << 8) | blue;
  }

  /** Apply the wrap mode to (u, v), then sample; out-of-bounds under CLIP returns background. */
  private static int sampleWrapped(
    int[] argb, int width, int height, double u, double v, ProjectionParams params, int background) {

    switch (params.wrapMode) {
      case CLAMP:
        u = clamp01(u);
        v = clamp01(v);
        break;

      case TILE:
        u = tile(u);
        v = tile(v);
        break;

      case MIRROR:
        u = mirror(u);
        v = mirror(v);
        break;

      case CLIP:
        if (u < 0 || u > 1 || v < 0 || v > 1) {
          return background;
        }
        break;
    }

    return sample(argb, width, height, u, v, params.interpolation);
  }

  /**
   * Sample the frame at texture coordinate (u, v) in [0, 1]. v runs 0 (bottom) to 1 (top),
   * while image rows run top-down, so the row is flipped.
   */
  private static int sample(
    int[] argb, int width, int height, double u, double v, ProjectionParams.Interpolation interpolation) {

    final double pixelX = u * (width - 1);
    final double pixelY = (1.0 - v) * (height - 1);

    if (interpolation == ProjectionParams.Interpolation.NEAREST) {
      final int nearestX = clampInt((int) Math.round(pixelX), 0, width - 1);
      final int nearestY = clampInt((int) Math.round(pixelY), 0, height - 1);

      return argb[nearestY * width + nearestX];
    }

    final int lowX = clampInt((int) Math.floor(pixelX), 0, width - 1);
    final int lowY = clampInt((int) Math.floor(pixelY), 0, height - 1);
    final int highX = Math.min(lowX + 1, width - 1);
    final int highY = Math.min(lowY + 1, height - 1);

    final double fractionX = pixelX - Math.floor(pixelX);
    final double fractionY = pixelY - Math.floor(pixelY);

    final int topLeft = argb[lowY * width + lowX];
    final int topRight = argb[lowY * width + highX];
    final int bottomLeft = argb[highY * width + lowX];
    final int bottomRight = argb[highY * width + highX];

    final int top = lerpColor(topLeft, topRight, fractionX);
    final int bottom = lerpColor(bottomLeft, bottomRight, fractionX);

    return lerpColor(top, bottom, fractionY);
  }

  /** Per-channel linear interpolation between two packed ARGB colours. */
  private static int lerpColor(int colorA, int colorB, double amount) {
    final int alphaA = (colorA >>> 24) & 0xff;
    final int redA = (colorA >> 16) & 0xff;
    final int greenA = (colorA >> 8) & 0xff;
    final int blueA = colorA & 0xff;

    final int alphaB = (colorB >>> 24) & 0xff;
    final int redB = (colorB >> 16) & 0xff;
    final int greenB = (colorB >> 8) & 0xff;
    final int blueB = colorB & 0xff;

    final int alpha = (int) Math.round(alphaA + (alphaB - alphaA) * amount);
    final int red = (int) Math.round(redA + (redB - redA) * amount);
    final int green = (int) Math.round(greenA + (greenB - greenA) * amount);
    final int blue = (int) Math.round(blueA + (blueB - blueA) * amount);

    return (alpha << 24) | (red << 16) | (green << 8) | blue;
  }

  private static double clamp01(double value) {
    if (value < 0) {
      return 0;
    }

    if (value > 1) {
      return 1;
    }

    return value;
  }

  private static double tile(double value) {
    return value - Math.floor(value);
  }

  private static double mirror(double value) {
    final double wrapped = Math.abs(value) % 2.0;
    return wrapped > 1.0 ? 2.0 - wrapped : wrapped;
  }

  private static int clampInt(int value, int low, int high) {
    if (value < low) {
      return low;
    }

    if (value > high) {
      return high;
    }

    return value;
  }
}
