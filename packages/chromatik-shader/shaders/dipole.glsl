// Dipole
//
// The logarithm of a Mobius ratio, log((z - a) / (z - b)). Its imaginary part is the angle a
// point sees between the two anchors and its real part how much nearer it sits to one than the
// other, so colouring by a blend of the two gives bands that pour out of one anchor, spiral
// across the frame and drain into the other. The anchors drift about, and twist leans the bands
// from plain rings into spirals.

uniform float time;
uniform vec2 resolution;

uniform float twist;     // @range(-3, 3) @default(1.2)
uniform float windings;  // @range(1, 6) @default(2)
uniform float flow;      // @range(-2, 2) @default(0.6)
uniform float drift;     // @range(0, 1) @default(0.15)
uniform float hue;       // @range(0, 1) @default(0.95)
uniform float spread;    // @range(0, 1) @default(0.3)
uniform float zoom;      // @range(0.5, 4) @default(1.2)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

vec2 complexDivide(vec2 a, vec2 b) {
  float denominator = max(dot(b, b), 1e-6);
  return vec2(a.x * b.x + a.y * b.y, a.y * b.x - a.x * b.y) / denominator;
}

vec2 complexLog(vec2 z) {
  return vec2(log(max(length(z), 1e-6)), atan(z.y, z.x));
}

void main(void) {
  vec2 z = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * zoom;
  float wander = time * drift;
  vec2 anchorA = 0.6 * vec2(cos(wander), sin(wander * 0.7));
  vec2 anchorB = -0.6 * vec2(cos(wander * 0.8 + 1.0), sin(wander * 0.6 + 2.0));

  vec2 w = complexLog(complexDivide(z - anchorA, z - anchorB));
  float turns = floor(windings + 0.5);
  float spiral = (turns * w.y + twist * w.x) / 6.2831853;
  float rings = 0.5 + 0.5 * cos(w.x * 3.0 - time * flow * 2.0);

  float colorPhase = spiral + time * flow * 0.1;
  vec3 color = hsv2rgb(vec3(hue + 0.5 * spread * sin(6.2831853 * colorPhase), 0.9, 0.55 + 0.45 * rings));

  gl_FragColor = vec4(color, 1.0);
}
