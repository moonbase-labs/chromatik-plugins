// Wonka Tunnel
//
// The boat ride, through a tunnel tiled like a turtle's shell but dyed in the primaries. Seen from
// the bow, the walls are irregular scutes of red, green and blue, two shades of each, every scute
// darker toward its rim with faint growth rings inside, lit by whirling lamps, the whole tunnel
// lurching and wobbling as it goes. The far end is black. Nothing in it ever reaches white: where lights pile up the colour is scaled down
// rather than clipped, so it stays dyed. Speed below zero runs the ride backwards.
// The palette is three families with a hue knob each: hue1 (the reds), hue2
// (the greens) and hue3 (the blues). The first six controls reach a MIDI surface, so the hues sit
// right after speed and wobble is the one left to the panel.

uniform float time;
uniform vec2 resolution;

uniform float speed;   // @range(-3, 3) @default(1)
uniform float hue1;    // @range(-0.5, 0.5) @default(0)
uniform float hue2;    // @range(-0.5, 0.5) @default(0)
uniform float hue3;    // @range(-0.5, 0.5) @default(0)
uniform float spin;    // @range(-2, 2) @default(0.4)
uniform float twist;   // @range(0, 2) @default(0.6)
uniform float wobble;  // @range(0, 1) @default(0.5)

const float TAU = 6.2831853;

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

float hash(float n) {
  return fract(sin(n * 127.1) * 43758.5453);
}

float hash2(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float valueNoise(vec2 p) {
  vec2 cell = floor(p);
  vec2 local = fract(p);
  vec2 fade = local * local * (3.0 - 2.0 * local);
  float a = hash2(cell);
  float b = hash2(cell + vec2(1.0, 0.0));
  float c = hash2(cell + vec2(0.0, 1.0));
  float d = hash2(cell + vec2(1.0, 1.0));
  return mix(mix(a, b, fade.x), mix(c, d, fade.x), fade.y);
}

// The palette: a light and a dark scute of each primary, as hue, saturation and value, each
// shifted by its family's knob. Picked by index, so a scute is one colour and its neighbour is a
// different one.
vec3 scuteColor(float index) {
  float pick = mod(floor(index), 6.0);
  if (pick < 0.5) return vec3(0.00 + hue1, 0.95, 0.60);   // red
  if (pick < 1.5) return vec3(0.33 + hue2, 0.90, 0.52);   // green
  if (pick < 2.5) return vec3(0.66 + hue3, 0.90, 0.62);   // blue
  if (pick < 3.5) return vec3(0.33 + hue2, 0.95, 0.28);   // dark green
  if (pick < 4.5) return vec3(0.66 + hue3, 0.85, 0.34);   // dark blue
  return vec3(0.00 + hue1, 0.95, 0.32);                   // dark red
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y);

  // The tunnel lurches: its far end wanders and the view rolls, more the harder it is pushed.
  float lurch = wobble * (0.6 + 0.4 * abs(speed) / 3.0);
  vec2 centre = 0.18 * lurch * vec2(sin(time * 0.9) + 0.5 * sin(time * 2.3), cos(time * 0.7));
  vec2 p = uv - centre;
  float roll = 0.3 * lurch * sin(time * 0.5);
  p = mat2(cos(roll), -sin(roll), sin(roll), cos(roll)) * p;

  // Polar tunnel: distance from the centre is depth, angle is position round the wall.
  float radius = length(p);
  float angle = atan(p.y, p.x);
  float travel = time * speed * 1.5;
  float depth = 1.0 / max(radius, 0.002) + travel;

  // The wall breathes: a kaleidoscopic ripple that bends the bands as they pass.
  angle += 0.15 * wobble * sin(depth * 0.7 + time * 1.3) * sin(angle * 3.0);
  float wall = angle / TAU + depth * 0.05 * twist + time * spin * 0.2;

  // The wall is tiled with irregular polygons: a Voronoi cell for every jittered seed on the
  // unwrapped wall, each pane a flat colour, with dark grout between them. The seed lattice
  // repeats every eight cells round the tunnel, so the join where the angle wraps is seamless.
  const float AROUND = 8.0;
  vec2 cellSpace = vec2(wall * AROUND, depth * 1.2);
  vec2 cell = floor(cellSpace);
  vec2 local = fract(cellSpace);
  float nearest = 8.0;
  float second = 8.0;
  vec2 nearestId = cell;

  for (int offsetY = -1; offsetY <= 1; offsetY++) {
    for (int offsetX = -1; offsetX <= 1; offsetX++) {
      vec2 neighbour = vec2(float(offsetX), float(offsetY));
      vec2 id = cell + neighbour;
      vec2 wrappedId = vec2(mod(id.x, AROUND), id.y);
      vec2 jitter = vec2(hash2(wrappedId), hash2(wrappedId + 19.0)) * 0.8 + 0.1;
      vec2 seed = neighbour + jitter;
      float distanceToSeed = length(seed - local);

      if (distanceToSeed < nearest) {
        second = nearest;
        nearest = distanceToSeed;
        nearestId = wrappedId;
      } else if (distanceToSeed < second) {
        second = distanceToSeed;
      }
    }
  }

  vec3 scute = scuteColor(floor(hash2(nearestId + 3.0) * 6.0));
  float grout = smoothstep(0.0, 0.05, second - nearest);
  // A scute is darkest at its rim and lifts toward the middle, with faint growth rings in it.
  float rimShade = mix(0.45, 1.15, smoothstep(0.0, 0.45, second - nearest));
  float rings = 1.0 - 0.12 * (0.5 + 0.5 * sin(nearest * 34.0));
  // Most of the wall sits in shadow; the colour shows where the whirling lamps happen to fall.
  float lit = smoothstep(0.25, 0.85, valueNoise(vec2(wall * 3.0 + time * 0.15, depth * 0.35 - time * 0.3)));
  vec3 bands = hsv2rgb(scute) * grout * rimShade * rings * (0.25 + 0.75 * lit);

  vec3 color = bands;

  // The far end is black.
  float distanceFade = smoothstep(0.0, 0.25, radius);
  color *= distanceFade * 0.9;

  // Keep it dyed: where the lights pile up, bring the whole colour down to fit rather than
  // letting the channels clip toward white, and pull the least channel down so no pixel greys.
  float peak = max(color.r, max(color.g, color.b));
  color /= max(peak, 1.0);
  float floorChannel = min(color.r, min(color.g, color.b));
  color -= floorChannel * 0.5;

  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
