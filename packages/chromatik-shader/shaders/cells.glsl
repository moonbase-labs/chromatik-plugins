// Cells
//
// Voronoi cells whose seeds wander, so the membranes between them stretch and snap as a
// neighbour comes and goes. Each cell keeps its own hue and breathes on its own clock, and the
// membranes carry the cell's colour at full brightness, so the frame reads as lit tissue rather
// than a wire grid.

uniform float time;
uniform vec2 resolution;

uniform float count;     // @range(2, 12) @default(5)
uniform float wander;    // @range(0, 2) @default(0.5)
uniform float membrane;  // @range(0.02, 0.5) @default(0.15)
uniform float hue;       // @range(0, 1) @default(0.55)
uniform float spread;    // @range(0, 1) @default(0.5)
uniform float fill;      // @range(0, 1) @default(0.3)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

vec2 hash2(vec2 cell) {
  vec3 seed = fract(vec3(cell.xyx) * vec3(0.1031, 0.1030, 0.0973));
  seed += dot(seed, seed.yzx + 33.33);
  return fract((seed.xx + seed.yz) * seed.zy);
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y);
  vec2 point = uv * count;
  vec2 cell = floor(point);
  vec2 local = fract(point);

  float nearest = 8.0;
  float second = 8.0;
  vec2 nearestCell = cell;

  for (int offsetY = -1; offsetY <= 1; offsetY++) {
    for (int offsetX = -1; offsetX <= 1; offsetX++) {
      vec2 neighbour = vec2(float(offsetX), float(offsetY));
      vec2 random = hash2(cell + neighbour);
      vec2 seed = neighbour + 0.5 + 0.4 * sin(time * wander * (0.5 + random) + random * 6.2831853);
      float distanceToSeed = length(seed - local);

      if (distanceToSeed < nearest) {
        second = nearest;
        nearest = distanceToSeed;
        nearestCell = cell + neighbour;
      } else if (distanceToSeed < second) {
        second = distanceToSeed;
      }
    }
  }

  vec2 identity = hash2(nearestCell);
  float border = 1.0 - smoothstep(0.0, membrane, second - nearest);
  float breath = 0.5 + 0.5 * sin(time * (0.6 + identity.y) + identity.x * 6.2831853);
  float cellHue = hue + spread * identity.x;

  vec3 cellColor = hsv2rgb(vec3(cellHue, 0.9, 1.0));
  vec3 color = cellColor * mix(fill * (0.4 + 0.6 * breath), 1.0, border);
  color += vec3(0.35) * border * border;

  gl_FragColor = vec4(color, 1.0);
}
