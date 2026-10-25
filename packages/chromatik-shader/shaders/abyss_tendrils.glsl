// Abyss Tendrils
//
// Bioluminescent filaments drifting in deep water. Ridged fractal noise, fed through a slow
// domain warp, draws a tangle of thin bright threads that sway and fold like something alive;
// two scales of it give trunks and the finer hairs that branch off them. Pulses of light run
// along the threads, the water above carries a faint shifting caustic, and marine snow sinks
// through the whole frame. Abyssal navy below, teal and cyan where the tendrils glow, with a
// violet cast at the brightest tips.

uniform float time;
uniform vec2 resolution;

uniform float zoom;      // @range(0.5, 4) @default(1.6)
uniform float drift;     // @range(0, 2) @default(0.5)
uniform float tendrils;  // @range(1, 6) @default(2.5)
uniform float glow;      // @range(0, 1) @default(0.4)
uniform float pulse;     // @range(0, 1) @default(0.6)
uniform float hue;       // @range(-0.5, 0.5) @default(0)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
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

float fbm(vec2 p) {
  float sum = 0.0;
  float amplitude = 0.5;
  mat2 twist = mat2(0.8, 0.6, -0.6, 0.8) * 2.03;

  for (int octave = 0; octave < 4; octave++) {
    sum += amplitude * valueNoise(p);
    p = twist * p + vec2(1.7, 9.2);
    amplitude *= 0.5;
  }

  return sum;
}

// A thread wherever the noise crosses its midpoint: the closer to the crossing, the brighter.
// Sharpness sets how thin the thread is.
float ridge(float noise, float sharpness) {
  return pow(1.0 - abs(2.0 * noise - 1.0), sharpness);
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y);
  vec2 p = uv * zoom;
  float t = time * drift;

  // The water itself: a slow sway of the whole field, as if seen through a current.
  p += 0.12 * vec2(sin(p.y * 2.0 + t * 0.7), cos(p.x * 1.7 - t * 0.5));

  // Domain warp: the threads fold and drift because the space they are drawn in does.
  vec2 flow = vec2(
    fbm(p + vec2(0.0, t * 0.25)),
    fbm(p + vec2(3.1, 1.7) - vec2(t * 0.18, 0.0)));
  vec2 warped = p + 1.4 * (flow - 0.5);

  // Trunks and hairs: two scales of ridged noise in the warped space.
  float sharpness = mix(18.0, 5.0, glow);
  float trunk = ridge(fbm(warped * tendrils), sharpness);
  float hair = ridge(fbm(warped * tendrils * 2.6 + vec2(5.0, 2.0)), sharpness * 1.4);
  float thread = trunk + 0.45 * hair;

  // Light travelling along the threads: a wave in the warp field, so it follows their course.
  float travel = sin((flow.x + flow.y) * 18.0 - time * 2.4) * 0.5 + 0.5;
  thread *= mix(1.0, 0.35 + 0.95 * travel, pulse);

  // Where the hairs meet trunks the thread is thick; that is where it burns brightest and whitest.
  float tip = smoothstep(0.9, 1.5, thread);

  // Colour: deep blue at the faint edges, cyan where the threads glow hard, violet at the
  // hottest tips.
  float threadHue = mix(mix(0.58, 0.50, smoothstep(0.2, 1.0, thread)), 0.74, tip) + hue;
  vec3 threadColor = hsv2rgb(vec3(threadHue, mix(0.95, 0.35, tip), 1.0));

  // The water: abyssal navy fading to black with depth.
  float depth = smoothstep(0.6, -0.6, uv.y);
  vec3 water = hsv2rgb(vec3(0.62 + hue, 0.95, mix(0.10, 0.015, depth)));

  // A faint caustic shimmer from far above, only in the upper water.
  float caustic = ridge(fbm(uv * 3.0 + vec2(time * 0.08, time * 0.05)), 6.0);
  water += hsv2rgb(vec3(0.50 + hue, 0.7, 1.0)) * caustic * 0.08 * (1.0 - depth);

  // Marine snow: sparse specks sinking slowly, each on its own drift.
  vec2 snowSpace = uv * 40.0 + vec2(0.0, time * 1.2);
  vec2 snowCell = floor(snowSpace);
  float snowSeed = hash2(snowCell);
  vec2 snowOffset = fract(snowSpace) - vec2(hash2(snowCell + 7.0), hash2(snowCell + 13.0));
  float snow = step(0.985, snowSeed) * smoothstep(0.12, 0.0, length(snowOffset));

  vec3 color = water;
  color += threadColor * thread * mix(0.6, 1.3, glow);
  color += vec3(0.6, 0.8, 0.9) * snow * 0.4;

  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
