// Truchet
//
// Square Truchet tiles: each carries two quarter-circle arcs, laid one way or the other by a
// hash of its position, and the arcs join across tile edges into pipework that winds over the
// whole frame with no plan behind it. Light runs along the pipes, colour sweeps across them,
// and tiles flip now and then, so a path that led one way a moment ago is suddenly wired
// elsewhere.

uniform float time;
uniform vec2 resolution;

uniform float tiles;     // @range(2, 12) @default(5)
uniform float width;     // @range(0.05, 0.45) @default(0.3)
uniform float flow;      // @range(0, 3) @default(1)
uniform float rewire;    // @range(0, 2) @default(0.15)
uniform float hue;       // @range(0, 1) @default(0.1)
uniform float spread;    // @range(0, 1) @default(0.5)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

float hash(vec2 cell) {
  return fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y);
  vec2 point = uv * tiles;
  vec2 cell = floor(point);
  vec2 local = fract(point);

  float random = hash(cell);
  float flips = floor(time * rewire * (0.3 + random) + random * 7.0);
  float orientation = mod(floor(random * 2.0) + flips, 2.0);

  if (orientation > 0.5) {
    local.x = 1.0 - local.x;
  }

  float arcA = abs(length(local) - 0.5);
  float arcB = abs(length(local - 1.0) - 0.5);
  float nearest = min(arcA, arcB);
  float pipe = 1.0 - smoothstep(width * 0.5, width, nearest);

  float angle = arcA < arcB ? atan(local.y, local.x) : atan(1.0 - local.y, 1.0 - local.x);
  float along = angle / 1.5707963;
  float pulse = 0.7 + 0.3 * sin(along * 6.2831853 + time * flow * 2.0);

  float sweep = 0.5 + 0.5 * sin((point.x + point.y) * 0.8 - time * flow);
  vec3 pipeColor = hsv2rgb(vec3(hue + spread * sweep, 0.9, 1.0)) * pulse;
  vec3 background = hsv2rgb(vec3(hue + 0.5, 0.8, 0.1));

  gl_FragColor = vec4(mix(background, pipeColor, pipe), 1.0);
}
