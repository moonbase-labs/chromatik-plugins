// Paisley
//
// A few rounds of z -> z^2 + c, then domain colouring of where each point ended up: hue from the
// argument, rings from the modulus. With only a handful of rounds the Julia set's filaments are
// still broad swirls rather than dust, and c walks the edge of the Mandelbrot set, where the
// shape never settles, so the swirls keep folding into new ones.

uniform float time;
uniform vec2 resolution;

uniform float depth;    // @range(1, 6) @default(3)
uniform float walk;     // @range(0, 1) @default(0.05)
uniform float rings;    // @range(0, 4) @default(1)
uniform float spin;     // @range(-1, 1) @default(0.05)
uniform float hue;      // @range(0, 1) @default(0.36)
uniform float spread;   // @range(0, 1) @default(0.35)
uniform float zoom;     // @range(0.5, 3) @default(1.5)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * zoom;
  float turn = time * spin;
  vec2 z = vec2(
      uv.x * cos(turn) - uv.y * sin(turn),
      uv.x * sin(turn) + uv.y * cos(turn));

  float theta = time * walk;
  vec2 c = 0.5 * vec2(cos(theta), sin(theta)) - 0.25 * vec2(cos(2.0 * theta), sin(2.0 * theta));
  int rounds = int(depth + 0.5);

  for (int index = 0; index < 6; index++) {
    if (index >= rounds || dot(z, z) > 1.0e12) {
      break;
    }

    z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
  }

  float argument = atan(z.y, z.x) / 6.2831853 + time * 0.03;
  float band = fract(log(max(length(z), 1e-5)) * rings);
  float localHue = hue + 0.5 * spread * sin(6.2831853 * argument);

  gl_FragColor = vec4(hsv2rgb(vec3(localHue, 0.9, 0.6 + 0.4 * band)), 1.0);
}
