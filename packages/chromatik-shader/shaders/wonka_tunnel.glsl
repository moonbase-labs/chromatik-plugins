// Wonka Tunnel
//
// The boat ride. A tunnel seen from the bow, its walls lit by whirling projected colour: bands of
// acid magenta, lime, electric blue, orange and yellow chasing each other down the walls, a spiral
// of lights corkscrewing past, smears of coloured light sliding over everything, and the whole
// tunnel lurching and wobbling as it goes. The far end is black. Now and then the lights strobe
// out and the frame goes white, red or green for a flash. Speed below zero runs the ride
// backwards.

uniform float time;
uniform vec2 resolution;

uniform float speed;   // @range(-3, 3) @default(1)
uniform float spin;    // @range(-2, 2) @default(0.4)
uniform float twist;   // @range(0, 2) @default(0.6)
uniform float wobble;  // @range(0, 1) @default(0.5)
uniform float strobe;  // @range(0, 1) @default(0.4)
uniform float hue;     // @range(-0.5, 0.5) @default(0)

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

// The ride's palette: six hues that were never meant to sit next to each other. Picked by index,
// so a band is one flat colour and the next band is a different one.
float wonkaHue(float index) {
  float pick = mod(floor(index), 6.0);
  if (pick < 0.5) return 0.88;   // magenta
  if (pick < 1.5) return 0.27;   // lime
  if (pick < 2.5) return 0.62;   // electric blue
  if (pick < 3.5) return 0.06;   // orange
  if (pick < 4.5) return 0.78;   // purple
  return 0.97;                   // red
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

  // Bands of flat colour racing down the walls, split into segments round the wall so the
  // colours checker rather than ring.
  float bandIndex = floor(depth * 1.2);
  float segmentIndex = floor(wall * 8.0);
  float bandHue = wonkaHue(bandIndex * 3.0 + segmentIndex * 1.0 + floor(hash(bandIndex) * 6.0)) + hue;
  float bandEdge = smoothstep(0.0, 0.08, fract(depth * 1.2)) * smoothstep(1.0, 0.92, fract(depth * 1.2));
  float segmentEdge = smoothstep(0.0, 0.06, fract(wall * 8.0)) * smoothstep(1.0, 0.94, fract(wall * 8.0));
  // Most of the wall sits in shadow; the colour shows where the whirling lamps happen to fall.
  float lit = smoothstep(0.25, 0.85, valueNoise(vec2(wall * 3.0 + time * 0.15, depth * 0.35 - time * 0.3)));
  vec3 bands = hsv2rgb(vec3(bandHue, 0.95, 0.55)) * bandEdge * segmentEdge * (0.15 + 0.85 * lit);

  // Projected light: coloured smears sliding over the wall, as from a lamp through a gel.
  float smear = valueNoise(vec2(wall * 6.0, depth * 0.5 - time * 0.4));
  smear = smoothstep(0.35, 0.8, smear);
  float smearHue = wonkaHue(floor(depth * 0.3 + time * 0.2) + 2.0) + hue;
  vec3 smears = hsv2rgb(vec3(smearHue, 0.85, 1.0)) * smear * 0.8;

  // The spiral: a string of bright lights corkscrewing down the tunnel.
  float spiral = fract(wall * 2.0 - depth * 0.35);
  float lights = smoothstep(0.08, 0.0, abs(spiral - 0.5) - 0.02);
  float bulb = smoothstep(0.5, 0.0, abs(fract(depth * 2.5) - 0.5));
  vec3 spiralColor = hsv2rgb(vec3(wonkaHue(floor(depth * 2.5) + 1.0) + hue, 0.4, 1.0));
  vec3 spiralLights = spiralColor * lights * bulb * 1.5;

  vec3 color = bands + smears + spiralLights;

  // The far end is black; the near wall is bright. Movement blurs the near wall into streaks.
  float distanceFade = smoothstep(0.0, 0.25, radius);
  float nearGlow = smoothstep(0.3, 0.9, radius) * 0.12;
  color *= distanceFade * 0.9;
  color += color * nearGlow;

  // Strobe: the lights cut out and a flat colour slams the whole frame for a few frames.
  float flashSeed = hash(floor(time * 7.0));
  float flashOn = step(1.0 - 0.12 * strobe, flashSeed);
  float flashHue = wonkaHue(floor(flashSeed * 60.0)) + hue;
  vec3 flashColor = mix(vec3(1.0), hsv2rgb(vec3(flashHue, 1.0, 1.0)), step(0.5, fract(flashSeed * 7.0)));
  float blackout = step(1.0 - 0.06 * strobe, hash(floor(time * 7.0) + 0.5));
  color = mix(color, flashColor, flashOn);
  color *= 1.0 - blackout;

  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
