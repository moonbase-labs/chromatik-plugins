package laserphile.chromatik.video;

/**
 * A per-frame snapshot of the projection controls, computed once on the engine thread so the
 * per-point loop in {@link Projector} stays cheap (no trig per point).
 *
 * Inputs are populated from the pattern's parameters each frame; {@link #recompute()} turns
 * the rotation angles into a rotation matrix and the scales into reciprocals.
 */
// Public because EnumParameter reflects on the nested enums' values() across packages; that
// requires both the enums and their enclosing class to be public.
public final class ProjectionParams {

  public enum WrapMode { CLAMP, CLIP, TILE, MIRROR }

  public enum BackgroundMode { BLACK, CLEAR }

  public enum Interpolation { NEAREST, BILINEAR }

  // Inputs (set each frame before recompute()).
  double yaw;
  double pitch;
  double roll;
  double translateX;
  double translateY;
  double translateZ;
  double scale = 1;
  double stretchX = 1;
  double stretchY = 1;
  double scrollX;
  double scrollY;
  WrapMode wrapMode = WrapMode.CLAMP;
  BackgroundMode backgroundMode = BackgroundMode.BLACK;
  Interpolation interpolation = Interpolation.BILINEAR;
  double level = 1;
  double gamma = 1;

  // Computed: the rotation matrix R (project() applies its transpose), and reciprocal scales.
  double m00, m01, m02;
  double m10, m11, m12;
  double m20, m21, m22;
  double invScaleX = 1;
  double invScaleY = 1;

  /**
   * Brightness and gamma folded into one channel value to channel value table, so the per-point
   * loop does a lookup rather than a {@link Math#pow}. Rebuilt only when one of the two moves,
   * which for a knob nobody is touching is never.
   */
  final int[] toneCurve = new int[256];

  /** True while the curve would change nothing, so the per-point loop can skip it outright. */
  boolean toneCurveIsFlat = true;

  // What the curve currently in the table was built from. NaN so the first recompute() builds it.
  private double curveLevel = Double.NaN;
  private double curveGamma = Double.NaN;

  void recompute() {
    final double yawRadians = Math.toRadians(this.yaw);
    final double pitchRadians = Math.toRadians(this.pitch);
    final double rollRadians = Math.toRadians(this.roll);

    final double cosYaw = Math.cos(yawRadians);
    final double sinYaw = Math.sin(yawRadians);
    final double cosPitch = Math.cos(pitchRadians);
    final double sinPitch = Math.sin(pitchRadians);
    final double cosRoll = Math.cos(rollRadians);
    final double sinRoll = Math.sin(rollRadians);

    // R = Rz(roll) * Ry(yaw) * Rx(pitch). project() samples with R-transpose (inverse rotation).
    this.m00 = cosRoll * cosYaw;
    this.m01 = cosRoll * sinYaw * sinPitch - sinRoll * cosPitch;
    this.m02 = cosRoll * sinYaw * cosPitch + sinRoll * sinPitch;

    this.m10 = sinRoll * cosYaw;
    this.m11 = sinRoll * sinYaw * sinPitch + cosRoll * cosPitch;
    this.m12 = sinRoll * sinYaw * cosPitch - cosRoll * sinPitch;

    this.m20 = -sinYaw;
    this.m21 = cosYaw * sinPitch;
    this.m22 = cosYaw * cosPitch;

    final double effectiveScaleX = this.scale * this.stretchX;
    final double effectiveScaleY = this.scale * this.stretchY;

    this.invScaleX = effectiveScaleX != 0 ? 1.0 / effectiveScaleX : 0;
    this.invScaleY = effectiveScaleY != 0 ? 1.0 / effectiveScaleY : 0;

    if (this.level != this.curveLevel || this.gamma != this.curveGamma) {
      rebuildToneCurve();
    }
  }

  /**
   * Video carries brightness the way a screen expects it, spread so that dark detail gets more of
   * the range than bright detail does. An LED answers its drive value far more directly, so those
   * same numbers land too bright through the middle and the picture looks washed out. Raising
   * gamma bends the middle back down; around 2.2 undoes the video's own curve entirely.
   */
  private void rebuildToneCurve() {
    for (int value = 0; value < this.toneCurve.length; value++) {
      final double corrected = Math.pow(value / 255.0, this.gamma) * this.level;

      this.toneCurve[value] = (int) Math.round(corrected * 255.0);
    }

    this.curveLevel = this.level;
    this.curveGamma = this.gamma;
    this.toneCurveIsFlat = this.level == 1 && this.gamma == 1;
  }
}
