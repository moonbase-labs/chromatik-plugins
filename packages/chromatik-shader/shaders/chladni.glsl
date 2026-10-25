// Chladni
//
// The standing waves of a square plate. Sand on a vibrating plate collects where the plate is
// still, along the nodal lines of cos(n pi x) cos(m pi y) - cos(m pi x) cos(n pi y). The mode
// numbers n and m slide continuously instead of stepping, so one figure melts into the next.
// The nodal lines glow, and the regions between them take their hue from which way the plate
// is moving at that moment.

uniform float time;
uniform vec2 resolution;

uniform float modes;    // @range(1, 8) @default(5)
uniform float morph;    // @range(0, 1) @default(0.15)
uniform float width;    // @range(0.02, 0.5) @default(0.08)
uniform float hue;      // @range(0, 1) @default(0.7)
uniform float spread;   // @range(0, 1) @default(0.3)
uniform float scale;    // @range(0.5, 3) @default(1)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

void main(void) {
  vec2 plate = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * scale;
  float n = 1.0 + (modes - 1.0) * (0.5 + 0.5 * sin(time * morph));
  float m = 1.0 + (modes - 1.0) * (0.5 + 0.5 * sin(time * morph * 0.37 + 2.1));

  float wave = cos(n * 3.14159265 * plate.x) * cos(m * 3.14159265 * plate.y)
             - cos(m * 3.14159265 * plate.x) * cos(n * 3.14159265 * plate.y);

  float pixelsWide = width * 20.0;
  float nodal = 1.0 - smoothstep(0.0, pixelsWide * fwidth(wave), abs(wave));
  float displacement = wave * sin(time * 1.5);

  vec3 region = hsv2rgb(vec3(hue + spread * displacement * 0.5, 0.9, 0.15 + 0.3 * abs(displacement)));
  vec3 line = hsv2rgb(vec3(hue + 0.5, 0.8, 0.8));

  gl_FragColor = vec4(mix(region, line, nodal), 1.0);
}
