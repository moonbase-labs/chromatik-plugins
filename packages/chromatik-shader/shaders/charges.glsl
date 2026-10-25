// Charges
//
// Four point charges, two of each sign, drifting about the frame. The stream function, each
// charge times the angle to it, is constant along field lines, and the potential, each charge
// times the log of the distance, is constant across them. Colouring hue by the one and brightness
// by the other draws the field lines in rainbow from positive to negative, crossed by
// equipotential rings that pulse outward.

uniform float time;
uniform vec2 resolution;

uniform float lines;    // @range(1, 6) @default(2)
uniform float rings;    // @range(0, 4) @default(1.5)
uniform float drift;    // @range(0, 1) @default(0.2)
uniform float pulse;    // @range(-2, 2) @default(0.6)
uniform float hue;      // @range(0, 1) @default(0.06)
uniform float spread;   // @range(0, 1) @default(0.22)
uniform float zoom;     // @range(0.5, 4) @default(1.3)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

void main(void) {
  vec2 z = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * zoom;
  float roam = time * drift;
  float streamFunction = 0.0;
  float potential = 0.0;

  for (int index = 0; index < 4; index++) {
    float phase = float(index) * 1.5707963;
    float polarity = (index == 0 || index == 2) ? 1.0 : -1.0;
    vec2 position = 0.7 * vec2(
        cos(roam * (0.6 + 0.1 * float(index)) + phase),
        sin(roam * (0.8 - 0.1 * float(index)) + phase * 1.3));
    vec2 offset = z - position;

    streamFunction += polarity * atan(offset.y, offset.x);
    potential += polarity * log(max(length(offset), 1e-4));
  }

  float turns = floor(lines + 0.5);
  float colorPhase = turns * streamFunction / 6.2831853 + time * 0.02;
  float band = 0.5 + 0.5 * cos(potential * rings * 2.0 - time * pulse);
  float localHue = hue + 0.5 * spread * sin(6.2831853 * colorPhase);

  gl_FragColor = vec4(hsv2rgb(vec3(localHue, 0.9, 0.55 + 0.45 * band)), 1.0);
}
