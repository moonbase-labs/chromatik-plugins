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

  // Computed: the rotation matrix R (project() applies its transpose), and reciprocal scales.
  double m00, m01, m02;
  double m10, m11, m12;
  double m20, m21, m22;
  double invScaleX = 1;
  double invScaleY = 1;

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
  }
}
