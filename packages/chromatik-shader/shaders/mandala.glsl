// Mandala
//
// The plane folded into a wheel of mirrored wedges, then domain coloured through the same kind
// of rational function as poles.glsl: hue from the argument, rings from the modulus. The zeros
// and poles live inside one wedge and breathe in and out there, and the fold copies them round
// the wheel, so the whole frame turns as one many-petalled flower. Mirroring each wedge is what
// keeps the colour continuous across the seams.

uniform float time;
uniform vec2 resolution;

uniform float petals;   // @range(3, 12) @default(6)
uniform float spin;     // @range(-1, 1) @default(0.1)
uniform float breathe;  // @range(0, 1) @default(0.3)
uniform float rings;    // @range(0, 4) @default(1.5)
uniform float hue;      // @range(0, 1) @default(0.78)
uniform float spread;   // @range(0, 1) @default(0.5)
uniform float zoom;     // @range(0.5, 4) @default(1.4)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

vec2 complexMultiply(vec2 a, vec2 b) {
  return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

vec2 complexDivide(vec2 a, vec2 b) {
  float denominator = max(dot(b, b), 1e-6);
  return vec2(a.x * b.x + a.y * b.y, a.y * b.x - a.x * b.y) / denominator;
}

vec2 polar(float radius, float angle) {
  return radius * vec2(cos(angle), sin(angle));
}

void main(void) {
  vec2 z = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * zoom;
  float wedges = floor(petals + 0.5);
  float sector = 6.2831853 / wedges;
  float halfSector = sector * 0.5;

  float angle = mod(atan(z.y, z.x) + time * spin, sector);
  angle = min(angle, sector - angle);
  vec2 folded = polar(length(z), angle);

  float pace = time * 0.3;
  vec2 value = vec2(1.0, 0.0);

  value = complexMultiply(value, folded - polar(0.6 + 0.3 * breathe * sin(pace), halfSector * 0.5));
  value = complexMultiply(value, folded - polar(0.25 + 0.2 * breathe * sin(pace * 0.7 + 1.0), halfSector * 0.9));
  value = complexDivide(value, folded - polar(1.1 + 0.3 * breathe * cos(pace * 0.5), halfSector * 0.2));
  value = complexDivide(value, folded);

  float argument = atan(value.y, value.x) / 6.2831853 + time * 0.03;
  float band = fract(log(max(length(value), 1e-5)) * rings);
  float localHue = hue + 0.5 * spread * sin(6.2831853 * argument);

  gl_FragColor = vec4(hsv2rgb(vec3(localHue, 0.9, 0.6 + 0.4 * band)), 1.0);
}
