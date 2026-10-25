// Singularity
//
// exp(1 / z), the textbook essential singularity: close to the point the function takes every
// value infinitely often, so the colour wheel winds round it without end and the rings pack
// tighter and tighter into a pair of lobes. The point wanders, and a plain winding is laid over
// the whole frame so the far side of the dome keeps turning too.

uniform float time;
uniform vec2 resolution;

uniform float reach;    // @range(0.1, 2) @default(0.6)
uniform float rings;    // @range(0, 4) @default(1.5)
uniform float winding;  // @range(0, 3) @default(1)
uniform float wander;   // @range(0, 1) @default(0.15)
uniform float hue;      // @range(0, 1) @default(0.58)
uniform float spread;   // @range(0, 1) @default(0.3)
uniform float zoom;     // @range(0.5, 4) @default(1.3)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

void main(void) {
  vec2 z = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * zoom;
  float roam = time * wander;
  vec2 point = 0.5 * vec2(cos(roam), sin(roam * 0.7));
  vec2 offset = z - point;
  float denominator = max(dot(offset, offset), 1e-5);
  vec2 inverse = reach * vec2(offset.x, -offset.y) / denominator;

  float turns = floor(winding + 0.5);
  float angle = atan(offset.y, offset.x);
  float colorPhase = (inverse.y + turns * angle) / 6.2831853 + time * 0.03;
  float settle = smoothstep(0.0, 0.15, length(offset));
  float band = 0.5 + 0.5 * cos(inverse.x * rings * 2.0 - time * 0.5) * settle;
  float localHue = hue + 0.5 * spread * sin(6.2831853 * colorPhase);

  gl_FragColor = vec4(hsv2rgb(vec3(localHue, 0.9, 0.55 + 0.45 * band)), 1.0);
}
