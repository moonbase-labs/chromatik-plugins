package laserphile.chromatik.transforms;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.command.LXCommand;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXListenableNormalizedParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.model.LXModel;
import heronarts.lx.model.LXPoint;
import heronarts.lx.structure.LXFixture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small calibration helper for fixture transforms.
 *
 * <p>When triggered, this applies a delta to yaw/pitch/roll on matching fixtures and writes the
 * values back to the fixture parameters themselves. This is for setup/calibration, not a visual
 * render effect.
 */
@LXCategory("Laserphile")
@LXComponent.Name("Panel Transforms")
public class PanelTransformEffect extends LXEffect {

  public enum Axis { YAW, PITCH, ROLL }

  public final EnumParameter<Axis> axis =
    new EnumParameter<Axis>("Axis", Axis.ROLL)
      .setDescription("Which fixture transform axis to edit");

  public final CompoundParameter stepDegrees =
    new CompoundParameter("Step", 120, 0.1, 180)
      .setDescription("Degrees to apply per trigger");

  public final BooleanParameter selectedOnly =
    new BooleanParameter("Selected", true)
      .setDescription("When on, only selected fixtures are changed");

  public final StringParameter tags =
    new StringParameter("Tags", "large")
      .setDescription("Comma-separated tags to match, blank means all");

  public final TriggerParameter rotatePlus =
    new TriggerParameter("Rotate+")
      .setDescription("Add Step degrees to the chosen axis");

  public final TriggerParameter rotateMinus =
    new TriggerParameter("Rotate-")
      .setDescription("Subtract Step degrees from the chosen axis");

  public final TriggerParameter zeroAxis =
    new TriggerParameter("Zero")
      .setDescription("Set the chosen axis to 0 on matching fixtures");

  private enum Operation { PLUS, MINUS, ZERO }

  private final Map<LXFixture, RotationBaseline> rotationBaselines = new IdentityHashMap<>();

  public PanelTransformEffect(LX lx) {
    super(lx);

    addParameter("axis", this.axis);
    addParameter("step", this.stepDegrees);
    addParameter("selected", this.selectedOnly);
    addParameter("tags", this.tags);
    addParameter("plus", this.rotatePlus);
    addParameter("minus", this.rotateMinus);
    addParameter("zero", this.zeroAxis);

    setRemoteControls(this.axis, this.stepDegrees, this.selectedOnly,
      this.rotatePlus, this.rotateMinus, this.zeroAxis);

    this.rotatePlus.onTrigger(() -> this.lx.engine.addTask(() -> applyOperation(Operation.PLUS)));
    this.rotateMinus.onTrigger(() -> this.lx.engine.addTask(() -> applyOperation(Operation.MINUS)));
    this.zeroAxis.onTrigger(() -> this.lx.engine.addTask(() -> applyOperation(Operation.ZERO)));
  }

  @Override
  protected void run(double deltaMs, double enabledAmount) {
    // This effect is a control surface only; it leaves channel colors unchanged.
  }

  private void applyOperation(Operation operation) {
    final double delta = switch (operation) {
      case PLUS -> this.stepDegrees.getValue();
      case MINUS -> -this.stepDegrees.getValue();
      case ZERO -> 0;
    };

    int touched = 0;

    for (LXFixture fixture : this.lx.structure.fixtures) {
      if (!matchesFixture(fixture)) {
        continue;
      }

      final Axis selectedAxis = this.axis.getEnum();

      if (selectedAxis == Axis.ROLL) {
        applyInPlaneOperation(fixture, operation, delta);
      } else {
        final Point3 beforeCentroid = centroid(fixture);
        applyAxisOperation(fixture, selectedAxis, operation, delta);
        preserveCentroid(fixture, beforeCentroid);
      }

      touched++;
    }

    if (touched == 0) {
      LX.log("[LaserphileTransforms] no fixtures matched current filter");
      return;
    }

    LX.log(String.format(Locale.US,
      "[LaserphileTransforms] %s %s on %d fixture(s)",
      axisLabel(this.axis.getEnum()),
      operationLabel(operation),
      touched));
  }

  private boolean matchesFixture(LXFixture fixture) {
    if (this.selectedOnly.isOn() && !fixture.selected.isOn()) {
      return false;
    }

    final List<String> requiredTags = parseTags(this.tags.getString());

    if (requiredTags.isEmpty()) {
      return true;
    }

    final Set<String> fixtureTags = parseTagSet(fixture.tags.getString());

    for (String required : requiredTags) {
      if (!fixtureTags.contains(required.toLowerCase(Locale.US))) {
        return false;
      }
    }

    return true;
  }

  private static LXListenableNormalizedParameter axisParameter(LXFixture fixture, Axis axis) {
    return switch (axis) {
      case YAW -> fixture.yaw;
      case PITCH -> fixture.pitch;
      case ROLL -> fixture.roll;
    };
  }

  private void applyAxisOperation(LXFixture fixture, Axis axis, Operation operation, double delta) {
    final LXListenableNormalizedParameter target = axisParameter(fixture, axis);
    final double nextValue = operation == Operation.ZERO ? 0 : target.getValue() + delta;

    this.lx.command.perform(new LXCommand.Parameter.SetValue(target, nextValue));
  }

  /**
   * Rotate around the fixture's own local normal, not a global axis.
   *
   * <p>For setup, this is the "spin the triangle in place" behavior: if the panel is already
   * tilted in space, a local roll is still in-plane with that tilted panel.
   */
  private void applyInPlaneOperation(LXFixture fixture, Operation operation, double deltaDegrees) {
    final RotationBaseline baseline = this.rotationBaselines.computeIfAbsent(
      fixture, PanelTransformEffect::captureBaseline);

    if (operation == Operation.ZERO) {
      baseline.orientation = 0;
      restoreBaseline(fixture, baseline);
      return;
    }

    // Triangle orientation is deliberately discrete. Do not derive cycle state from a floating
    // knob value: parameter serialization and normalization can turn 120 into 119.999..., which
    // prevents the third press from recognizing 360 and restoring the captured transform.
    baseline.orientation = Math.floorMod(
      baseline.orientation + (deltaDegrees >= 0 ? 1 : -1), 3);

    // State zero always restores the exact captured parameter values. It does not depend on
    // matrix decomposition or on three inverse translations cancelling numerically.
    if (baseline.orientation == 0) {
      restoreBaseline(fixture, baseline);
      LX.log("[LaserphileTransforms] triangle orientation 0/3 restored exactly");
      return;
    }

    final double angleDegrees = baseline.orientation * 120.0;
    final Mat3 localSpin = Mat3.fromAxisAngle(
      baseline.localNormal, Math.toRadians(angleDegrees));
    final Mat3 targetRotation = baseline.rotation.multiply(localSpin);
    final EulerAngles targetAngles = targetRotation.toYawPitchRoll();

    // The fixture origin must orbit the fixed world pivot as its local geometry rotates.
    final Point3 centerFromOrigin = baseline.center.subtract(baseline.position);
    final Point3 localCenterFromOrigin = baseline.rotation.transposeApply(centerFromOrigin);
    final Point3 rotatedCenterFromOrigin = targetRotation.apply(localCenterFromOrigin);
    final Point3 targetPosition = baseline.center.subtract(rotatedCenterFromOrigin);

    setTransform(fixture, targetPosition, targetAngles);
    LX.log(String.format(Locale.US,
      "[LaserphileTransforms] triangle orientation %d/3, center=(%.3f, %.3f, %.3f)",
      baseline.orientation, baseline.center.x, baseline.center.y, baseline.center.z));
  }

  private static RotationBaseline captureBaseline(LXFixture fixture) {
    final Point3 position = new Point3(
      fixture.x.getValue(), fixture.y.getValue(), fixture.z.getValue());
    final EulerAngles angles = new EulerAngles(
      fixture.yaw.getValue(), fixture.pitch.getValue(), fixture.roll.getValue());
    final Mat3 rotation = Mat3.fromYawPitchRoll(
      angles.yawDegrees, angles.pitchDegrees, angles.rollDegrees);
    final Point3 localNormal = rotation.transposeApply(planeNormal(fixture)).normalized();

    return new RotationBaseline(position, angles, rotation, centroid(fixture), localNormal);
  }

  private void restoreBaseline(LXFixture fixture, RotationBaseline baseline) {
    setTransform(fixture, baseline.position, baseline.angles);
  }

  private void setTransform(LXFixture fixture, Point3 position, EulerAngles angles) {
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.yaw, angles.yawDegrees));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.pitch, angles.pitchDegrees));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.roll, angles.rollDegrees));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.x, position.x));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.y, position.y));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.z, position.z));
  }

  private static List<String> parseTags(String raw) {
    final List<String> tags = new ArrayList<>();

    if (raw == null || raw.isBlank()) {
      return tags;
    }

    for (String token : raw.split(",")) {
      final String tag = token.trim().toLowerCase(Locale.US);

      if (!tag.isEmpty()) {
        tags.add(tag);
      }
    }

    return tags;
  }

  private static Set<String> parseTagSet(String raw) {
    final Set<String> tags = new HashSet<>();

    if (raw == null || raw.isBlank()) {
      return tags;
    }

    for (String token : raw.split(",")) {
      final String tag = token.trim().toLowerCase(Locale.US);

      if (!tag.isEmpty()) {
        tags.add(tag);
      }
    }

    return tags;
  }

  private static String axisLabel(Axis axis) {
    return switch (axis) {
      case YAW -> "yaw";
      case PITCH -> "pitch";
      case ROLL -> "roll";
    };
  }

  private static String operationLabel(Operation operation) {
    return switch (operation) {
      case PLUS -> "+= step";
      case MINUS -> "-= step";
      case ZERO -> "set to 0";
    };
  }

  /** Keep the fixture spinning in place by restoring its world-space centroid after rotation. */
  private void preserveCentroid(LXFixture fixture, Point3 beforeCentroid) {
    final Point3 afterCentroid = centroid(fixture);

    final double deltaX = beforeCentroid.x - afterCentroid.x;
    final double deltaY = beforeCentroid.y - afterCentroid.y;
    final double deltaZ = beforeCentroid.z - afterCentroid.z;

    if (Math.abs(deltaX) < 1e-6 && Math.abs(deltaY) < 1e-6 && Math.abs(deltaZ) < 1e-6) {
      return;
    }

    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.x, fixture.x.getValue() + deltaX));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.y, fixture.y.getValue() + deltaY));
    this.lx.command.perform(new LXCommand.Parameter.SetValue(fixture.z, fixture.z.getValue() + deltaZ));
  }

  private static Point3 centroid(LXFixture fixture) {
    final LXModel model = fixture.getModel();

    if (model == null || model.size == 0) {
      return new Point3(fixture.x.getValue(), fixture.y.getValue(), fixture.z.getValue());
    }

    double sumX = 0;
    double sumY = 0;
    double sumZ = 0;

    for (LXPoint point : model.points) {
      sumX += point.x;
      sumY += point.y;
      sumZ += point.z;
    }

    final double count = model.size;

    return new Point3(sumX / count, sumY / count, sumZ / count);
  }

  /** Plane normal from three maximally separated points; rigid transforms preserve their choice. */
  private static Point3 planeNormal(LXFixture fixture) {
    final LXModel model = fixture.getModel();

    if (model == null || model.size < 3) {
      return new Point3(0, 0, 1);
    }

    final LXPoint first = model.points[0];
    LXPoint second = first;
    double farthestDistance = -1;

    for (LXPoint candidate : model.points) {
      final double distance = squaredDistance(first, candidate);

      if (distance > farthestDistance) {
        farthestDistance = distance;
        second = candidate;
      }
    }

    final Point3 base = pointOf(second).subtract(pointOf(first));
    Point3 widestCross = new Point3(0, 0, 1);
    double widestArea = -1;

    for (LXPoint candidate : model.points) {
      final Point3 cross = base.cross(pointOf(candidate).subtract(pointOf(first)));
      final double area = cross.lengthSquared();

      if (area > widestArea) {
        widestArea = area;
        widestCross = cross;
      }
    }

    return widestCross.normalized();
  }

  private static double squaredDistance(LXPoint a, LXPoint b) {
    final double dx = a.x - b.x;
    final double dy = a.y - b.y;
    final double dz = a.z - b.z;

    return dx * dx + dy * dy + dz * dz;
  }

  private static Point3 pointOf(LXPoint point) {
    return new Point3(point.x, point.y, point.z);
  }

  private record Point3(double x, double y, double z) {

    Point3 subtract(Point3 other) {
      return new Point3(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    Point3 cross(Point3 other) {
      return new Point3(
        this.y * other.z - this.z * other.y,
        this.z * other.x - this.x * other.z,
        this.x * other.y - this.y * other.x
      );
    }

    double lengthSquared() {
      return this.x * this.x + this.y * this.y + this.z * this.z;
    }

    Point3 normalized() {
      final double length = Math.sqrt(lengthSquared());

      return length < 1e-12
        ? new Point3(0, 0, 1)
        : new Point3(this.x / length, this.y / length, this.z / length);
    }
  }

  private record EulerAngles(double yawDegrees, double pitchDegrees, double rollDegrees) {}

  private static final class RotationBaseline {

    final Point3 position;
    final EulerAngles angles;
    final Mat3 rotation;
    final Point3 center;
    final Point3 localNormal;
    int orientation = 0;

    RotationBaseline(
        Point3 position, EulerAngles angles, Mat3 rotation, Point3 center, Point3 localNormal) {
      this.position = position;
      this.angles = angles;
      this.rotation = rotation;
      this.center = center;
      this.localNormal = localNormal;
    }
  }

  private record Mat3(
    double m00, double m01, double m02,
    double m10, double m11, double m12,
    double m20, double m21, double m22
  ) {

    static Mat3 fromYawPitchRoll(double yawDegrees, double pitchDegrees, double rollDegrees) {
      final double yaw = Math.toRadians(yawDegrees);
      final double pitch = Math.toRadians(pitchDegrees);
      final double roll = Math.toRadians(rollDegrees);
      final double cy = Math.cos(yaw);
      final double sy = Math.sin(yaw);
      final double cp = Math.cos(pitch);
      final double sp = Math.sin(pitch);
      final double cr = Math.cos(roll);
      final double sr = Math.sin(roll);

      // LXFixture.computeGeometryMatrix(): R = Ry(yaw) * Rx(pitch) * Rz(roll).
      return new Mat3(
        cy * cr + sy * sp * sr, -cy * sr + sy * sp * cr, sy * cp,
        cp * sr, cp * cr, -sp,
        -sy * cr + cy * sp * sr, sy * sr + cy * sp * cr, cy * cp
      );
    }

    static Mat3 fromAxisAngle(Point3 axis, double radians) {
      final Point3 n = axis.normalized();
      final double c = Math.cos(radians);
      final double s = Math.sin(radians);
      final double t = 1 - c;

      return new Mat3(
        c + n.x * n.x * t, n.x * n.y * t - n.z * s, n.x * n.z * t + n.y * s,
        n.y * n.x * t + n.z * s, c + n.y * n.y * t, n.y * n.z * t - n.x * s,
        n.z * n.x * t - n.y * s, n.z * n.y * t + n.x * s, c + n.z * n.z * t
      );
    }

    Mat3 multiply(Mat3 other) {
      return new Mat3(
        this.m00 * other.m00 + this.m01 * other.m10 + this.m02 * other.m20,
        this.m00 * other.m01 + this.m01 * other.m11 + this.m02 * other.m21,
        this.m00 * other.m02 + this.m01 * other.m12 + this.m02 * other.m22,
        this.m10 * other.m00 + this.m11 * other.m10 + this.m12 * other.m20,
        this.m10 * other.m01 + this.m11 * other.m11 + this.m12 * other.m21,
        this.m10 * other.m02 + this.m11 * other.m12 + this.m12 * other.m22,
        this.m20 * other.m00 + this.m21 * other.m10 + this.m22 * other.m20,
        this.m20 * other.m01 + this.m21 * other.m11 + this.m22 * other.m21,
        this.m20 * other.m02 + this.m21 * other.m12 + this.m22 * other.m22
      );
    }

    Point3 transposeApply(Point3 vector) {
      return new Point3(
        this.m00 * vector.x + this.m10 * vector.y + this.m20 * vector.z,
        this.m01 * vector.x + this.m11 * vector.y + this.m21 * vector.z,
        this.m02 * vector.x + this.m12 * vector.y + this.m22 * vector.z
      );
    }

    Point3 apply(Point3 vector) {
      return new Point3(
        this.m00 * vector.x + this.m01 * vector.y + this.m02 * vector.z,
        this.m10 * vector.x + this.m11 * vector.y + this.m12 * vector.z,
        this.m20 * vector.x + this.m21 * vector.y + this.m22 * vector.z
      );
    }

    EulerAngles toYawPitchRoll() {
      final double pitch = Math.asin(clamp(-this.m12, -1, 1));
      final double cosPitch = Math.cos(pitch);
      final double yaw;
      final double roll;

      if (Math.abs(cosPitch) > 1e-6) {
        yaw = Math.atan2(this.m02, this.m22);
        roll = Math.atan2(this.m10, this.m11);
      } else {
        yaw = Math.atan2(-this.m20, this.m00);
        roll = 0;
      }

      return new EulerAngles(
        normalizeDegrees(Math.toDegrees(yaw)),
        normalizeDegrees(Math.toDegrees(pitch)),
        normalizeDegrees(Math.toDegrees(roll)));
    }
  }

  private static double clamp(double value, double low, double high) {
    return Math.max(low, Math.min(high, value));
  }

  private static double normalizeDegrees(double degrees) {
    double normalized = degrees % 360;

    if (normalized > 180) {
      normalized -= 360;
    } else if (normalized < -180) {
      normalized += 360;
    }

    return normalized;
  }

}
