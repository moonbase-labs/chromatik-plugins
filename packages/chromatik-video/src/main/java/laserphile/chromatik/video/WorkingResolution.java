package laserphile.chromatik.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;

/**
 * How big frames should be decoded, and how to make a grabber produce that size.
 *
 * <p>An LED model samples a few hundred to a few thousand points out of each frame, so decoding at
 * the source's own resolution is mostly wasted work: a 4K frame is 33 MB of pixels, of which a
 * couple of thousand are ever read. Shrinking on the way out costs almost nothing, because the
 * scaler is already converting every frame and resizing is part of the same pass, and it leaves a
 * buffer small enough to stay in cache while the projection walks it.
 */
final class WorkingResolution {

  /**
   * The smallest longest-edge worth decoding at. Below this the picture stops being recognisable
   * even on a coarse model, and the saving over 128 is not worth having.
   */
  private static final int SMALLEST_EDGE = 128;

  /** What the Res control offers. Every pattern that opens a source shows the same list. */
  static final String[] OPTIONS = { "128", "256", "384", "512", "Auto" };

  /** Which entry above is Auto, and so the default. */
  static final int AUTO_OPTION = 4;

  /** Longest edge per entry in {@link #OPTIONS}. 0 signals "work it out from the model". */
  private static final int[] EDGES = { 128, 256, 384, 512, 0 };

  /**
   * The chosen Res entry as a longest edge, working it out from the model when the entry is Auto.
   */
  static int edgeFor(int option, int pointCount) {
    final int chosen = EDGES[option];

    return chosen > 0 ? chosen : forPointCount(pointCount);
  }

  /**
   * Longest edge for a model of this many points, when the size is left on Auto.
   *
   * <p>Two pixels per point along each edge. A model's points are spread over two dimensions once
   * projected, so the square root turns a point count back into an edge, and doubling it leaves
   * enough detail that bilinear sampling has something to interpolate between rather than landing
   * on the same pixel as its neighbour.
   */
  static int forPointCount(int pointCount) {
    final int derived = (int) Math.round(2 * Math.sqrt(Math.max(0, pointCount)));

    return Math.max(SMALLEST_EDGE, derived);
  }

  /**
   * Ask the grabber for frames no larger than {@code longestEdge} on their longest side, keeping
   * the source's shape.
   *
   * <p>Call after {@code start()} and before the first grab. Before {@code start()} the size is
   * passed to the input as a requested capture mode, which a screen cannot satisfy, and the
   * source's own shape is not yet known so the aspect cannot be preserved. Afterwards it still
   * reaches the scaler in time to be the size frames come out at.
   *
   * <p>Never enlarges. Asking for 512 from a 320-wide clip gets 320, since inventing pixels only
   * costs memory and gives the projection nothing it did not already have.
   */
  static void applyTo(FFmpegFrameGrabber grabber, int longestEdge) {
    final int nativeWidth = grabber.getImageWidth();
    final int nativeHeight = grabber.getImageHeight();
    final int nativeLongestEdge = Math.max(nativeWidth, nativeHeight);

    if (longestEdge <= 0 || nativeLongestEdge <= longestEdge) {
      return;
    }

    final double shrink = longestEdge / (double) nativeLongestEdge;

    grabber.setImageWidth(Math.max(1, (int) Math.round(nativeWidth * shrink)));
    grabber.setImageHeight(Math.max(1, (int) Math.round(nativeHeight * shrink)));
  }

  private WorkingResolution() {
  }
}
