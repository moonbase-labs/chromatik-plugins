package laserphile.chromatik.shader;

/**
 * One {@code uniform} the shader declares, and the control range the author asked for.
 *
 * A shader says what it wants a value to mean but nothing about what it should sweep between, so
 * an unannotated uniform gets a plain 0 to 1 knob. An author who wants better can say so in a
 * trailing comment:
 *
 * <pre>
 *   uniform float depth;  // &#64;range(1, 12) &#64;default(4)
 * </pre>
 *
 * @param name the identifier the shader declared, which is also the key the value is saved under
 * @param type the GLSL type, kept as written so the uploader knows which glUniform call to make
 * @param minimum low end of the knob's travel
 * @param maximum high end of the knob's travel
 * @param defaultValue where the knob sits before anyone touches it, within the range above
 */
record UniformDeclaration(String name, String type, double minimum, double maximum,
    double defaultValue) {

  /** What an unannotated uniform gets: a plain normalised knob, resting in the middle. */
  static final double DEFAULT_MINIMUM = 0;
  static final double DEFAULT_MAXIMUM = 1;
  static final double DEFAULT_VALUE = 0.5;

  /**
   * Whether this is something a single knob can drive.
   *
   * Scalars are. A matrix or a vector is not: it would need a control per component, and the ones
   * that show up in practice are a mesh shader's transform rather than anything anyone would want
   * to sweep by hand. They are left undeclared to the controls and so sit at zero, which is what
   * GLSL gives an unset uniform anyway.
   */
  boolean isControllable() {
    return switch (this.type) {
      case "float", "int", "bool" -> true;
      default -> false;
    };
  }

  /**
   * Where the knob sits as a 0 to 1 position rather than as a value in the shader's own units,
   * which is what a normalised parameter stores and therefore what a saved project keeps.
   *
   * Storing the position rather than the value is what makes a range edit non-destructive: widen
   * @range and the knob stays where it was rather than jumping.
   */
  double defaultPosition() {
    final double span = this.maximum - this.minimum;

    if (span == 0) {
      return 0;
    }

    return Math.clamp((this.defaultValue - this.minimum) / span, 0, 1);
  }

  /** The shader-facing value for a knob sitting at the given 0 to 1 position. */
  double valueAt(double position) {
    return this.minimum + (position * (this.maximum - this.minimum));
  }
}
