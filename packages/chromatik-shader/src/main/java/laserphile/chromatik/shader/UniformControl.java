package laserphile.chromatik.shader;

import heronarts.lx.parameter.LXListenableNormalizedParameter;

/**
 * A uniform the shader declared, paired with the control that drives it.
 *
 * The control always stores a 0 to 1 position rather than the shader's own units. That is what a
 * normalised parameter is for, and it is what makes a project survive an edit to the shader: widen
 * a {@code @range} and the knob stays where it was rather than leaping, because what was saved was
 * the position and not the value.
 *
 * @param declaration what the source said, including the range to scale the position back into
 * @param parameter the knob or switch, registered on the pattern and saved with the project
 */
record UniformControl(UniformDeclaration declaration, LXListenableNormalizedParameter parameter) {

  /** The value to hand the shader, in the units it declared. */
  float value() {
    return (float) this.declaration.valueAt(this.parameter.getNormalized());
  }
}
