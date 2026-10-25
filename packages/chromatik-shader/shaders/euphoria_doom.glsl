// Euphoria / Doom
//
// A vaporwave horizon under a nebula, with a black hole where the sun should be, and a mood that
// cannot settle. The mood drifts between two states on three incommensurate sines, so it never
// loops, and the swing is wide enough to pin it at either end for a while. High: the hole is
// small, its photon ring blazes white-hot over teal, the accretion disc runs wide, fast and teal
// into magenta, the nebula billows bright and the floor grid burns while the frame breathes. Low:
// the hole swells and bends the grid and nebula into itself, the ring hardens to violet with a
// blood edge, the disc dims to embers, the clouds drain to bruised purple, a heartbeat quickens,
// the picture tears and the dark closes in from every side.

uniform float time;
uniform vec2 resolution;

uniform float mood;    // @range(-2, 2) @default(0)
uniform float swing;   // @range(0, 2) @default(1.2)
uniform float pace;    // @range(0, 2) @default(0.5)
uniform float pulse;   // @range(0, 1) @default(0.6)
uniform float decay;   // @range(0, 1) @default(0.6)
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

float fbm(vec2 p) {
  float sum = 0.0;
  float amplitude = 0.5;
  mat2 twist = mat2(0.8, 0.6, -0.6, 0.8) * 2.03;

  for (int octave = 0; octave < 5; octave++) {
    sum += amplitude * valueNoise(p);
    p = twist * p + vec2(1.7, 9.2);
    amplitude *= 0.5;
  }

  return sum;
}

// Two thumps per beat, the second softer, each dying away exponentially.
float heartbeat(float t, float rate) {
  float phase = fract(t * rate);
  float first = exp(-phase * 14.0);
  float second = 0.55 * exp(-max(phase - 0.28, 0.0) * 14.0) * step(0.28, phase);
  return first + second;
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y);

  // The mood: a bias from the knob, plus a wander that never repeats. The response is steep, so
  // it spends its time near one extreme or the other rather than in the middle.
  float t = time * max(pace, 0.001);
  float wander = 0.55 * sin(t * 0.21)
               + 0.30 * sin(t * 0.53 + 1.7)
               + 0.15 * sin(t * 1.31 + 0.4);
  float feeling = clamp(mood + swing * wander, -1.0, 1.0);
  float high = smoothstep(-0.8, 0.8, feeling);   // euphoria, 0..1
  float low = 1.0 - high;                        // dread, 0..1

  // Dread tears the picture horizontally, in bands that jump between frames.
  float tearRow = floor(uv.y * 40.0);
  float tearSeed = hash(tearRow + floor(time * 9.0));
  float dread = smoothstep(0.45, 1.0, low);   // tearing only once the mood has really turned
  float tear = step(1.0 - 0.5 * dread * dread, tearSeed) * (tearSeed - 0.5) * 0.35 * dread;
  uv.x += tear;

  // The heart: slow breathing when high, a hardening, quickening beat when low.
  float beatRate = mix(0.9, 3.2, low);
  float beat = heartbeat(time, beatRate);
  float breath = 0.5 + 0.5 * sin(time * 0.6);
  float throb = mix(0.12 * breath, 0.6 * beat, low) * pulse;

  // The hole: small and distant when high, swelling to devour the frame when low.
  float horizon = mix(-0.22, 0.0, high);
  vec2 holeCenter = vec2(0.0, horizon + mix(0.26, 0.22, low));
  float holeRadius = mix(0.11, 0.30, low) * (1.0 + 0.03 * throb);
  vec2 toHole = uv - holeCenter;
  float holeDistance = length(toHole);
  float radial = holeDistance / holeRadius;

  // Lensing: everything behind the hole is dragged toward it, hardest just outside the shadow,
  // so the grid and the sky bend around the ring. Stronger as the mood falls.
  float lensStrength = mix(0.45, 0.95, low);
  float lensFactor = max(1.0 - lensStrength / max(radial * radial, 1.0), 0.0);
  vec2 lensedUv = holeCenter + toHole * lensFactor;

  // Palette. Hues: teal 0.50, magenta 0.88, hot pink 0.92, purple 0.76, violet 0.70, blood 0.98.
  float skyHueTop = mix(0.72, 0.78, high) + hue;
  float skyHueHorizon = mix(0.99, 0.90, high) + hue;

  float skyMix = clamp((lensedUv.y - horizon) * 1.6, 0.0, 1.0);
  vec3 skyTop = hsv2rgb(vec3(skyHueTop, 0.9, mix(0.02, 0.10, high)));
  vec3 skyHorizon = hsv2rgb(vec3(skyHueHorizon, mix(1.0, 0.85, high), mix(0.12, 0.30, high)));
  vec3 sky = mix(skyHorizon, skyTop, skyMix);

  // Nebula: two stacks of fractal noise, one for how much gas there is and one for which gas it
  // is, so teal and magenta thread through each other with dark lanes between. Bright and
  // billowing when high; drained to bruised violet with a red underglow when low.
  vec2 cloudSpace = lensedUv * 2.2 + vec2(time * 0.02, -time * 0.012);
  float density = fbm(cloudSpace);
  density = fbm(cloudSpace + 1.6 * vec2(density, fbm(cloudSpace + 4.1)));
  float gas = smoothstep(0.35, 0.65, fbm(cloudSpace * 0.7 + vec2(7.3, 2.9)));
  float lanes = smoothstep(0.28, 0.72, density);
  float nebulaHue = mix(mix(0.70, 0.98, gas), mix(0.47, 0.90, gas), high) + hue;
  float nebulaValue = mix(0.22, 1.1, high) * lanes * lanes;
  vec3 nebula = hsv2rgb(vec3(nebulaHue, mix(0.95, 0.75, high), 1.0)) * nebulaValue;
  float sparkle = pow(hash2(floor(lensedUv * 120.0)), 40.0) * step(0.55, density) * high;
  sky += nebula + vec3(sparkle);

  // Floor: a perspective grid rushing toward the viewer, warped and starved by dread.
  vec3 floor3 = vec3(0.0);
  if (lensedUv.y < horizon) {
    float depth = horizon - lensedUv.y;
    float perspective = 1.0 / max(depth, 0.001);
    float wobble = (valueNoise(vec2(lensedUv.x * 6.0, time * 2.0)) - 0.5) * 0.9 * low;
    float gridX = lensedUv.x * perspective * 0.5 + wobble;
    float gridZ = perspective * 0.2 + time * mix(0.8, 3.0, low);
    float lineX = smoothstep(0.40, 0.48, abs(fract(gridX) - 0.5));
    float lineZ = smoothstep(0.38, 0.47, abs(fract(gridZ) - 0.5));
    float lines = clamp(lineX + lineZ, 0.0, 1.0);
    float fadeOut = smoothstep(0.0, 0.08, depth);
    float flicker = mix(1.0, 0.3 + 0.7 * step(0.35, hash(floor(time * 18.0))), low);
    vec3 lineColor = hsv2rgb(vec3(mix(0.80, 0.92, high) + hue, 0.9, mix(0.3, 1.3, high)));
    vec3 floorBase = hsv2rgb(vec3(mix(0.72, 0.82, high) + hue, 0.9, mix(0.01, 0.12, high)));
    floor3 = floorBase + lineColor * lines * fadeOut * flicker;
  }

  vec3 color = mix(sky, floor3, step(lensedUv.y, horizon));

  // Accretion disc: a flattened ring of matter streaming around the hole, streaked by noise in
  // polar space, brighter on the side coming toward us. Wide, fast and warm when high; a thin,
  // slow, violet ember when low.
  vec2 discSpace = vec2(toHole.x, toHole.y * 2.8);
  float discDistance = length(discSpace) / holeRadius;
  float angle = atan(discSpace.y, discSpace.x);
  float spin = time * mix(0.35, 1.4, high);
  float streaks = valueNoise(vec2(angle / TAU * 9.0 - spin * 2.0, log(discDistance) * 7.0));
  streaks *= valueNoise(vec2(angle / TAU * 23.0 + spin * 3.0, log(discDistance) * 11.0)) * 2.0;
  streaks = 0.15 + 0.85 * clamp(streaks, 0.0, 1.0);
  float discOuter = mix(2.2, 3.6, high);
  float discBand = smoothstep(1.05, 1.3, discDistance) * (1.0 - smoothstep(discOuter * 0.6, discOuter, discDistance));
  float beaming = 1.0 + mix(0.5, 0.9, high) * cos(angle + 0.4);
  float discHue = mix(mix(0.72, 0.98, smoothstep(1.0, discOuter, discDistance)),
                      mix(0.50, 0.90, smoothstep(1.0, discOuter, discDistance)), high) + hue;
  float discValue = mix(0.35, 0.85, high) * discBand * streaks * beaming;
  vec3 disc = hsv2rgb(vec3(discHue, mix(1.0, 0.92, high), 1.0)) * discValue;

  // Photon ring: a white-hot core hugging the shadow, a wide coloured band around it, and a
  // bloom that reaches well out, so the ring reads as the brightest thing in the frame at any
  // mood. Teal-white when high, violet with a blood edge when low.
  float coreWidth = mix(0.05, 0.07, high);
  float bandWidth = mix(0.16, 0.26, high);
  float core = exp(-pow((radial - 1.03) / coreWidth, 2.0));
  float band = exp(-pow((radial - 1.08) / bandWidth, 2.0));
  float halo = exp(-max(radial - 1.0, 0.0) * mix(3.5, 1.8, high)) * step(1.0, radial);
  float ringHue = mix(mix(0.74, 0.98, 0.5 + 0.5 * cos(angle * 2.0 + time)), 0.50, high) + hue;
  vec3 bandColor = hsv2rgb(vec3(ringHue, mix(0.95, 0.8, high), 1.0));
  vec3 coreColor = hsv2rgb(vec3(ringHue, mix(0.45, 0.15, high), 1.0));
  float ringValue = mix(1.3, 2.4, high) * (1.0 + 0.35 * throb);
  color += disc;
  color += coreColor * core * ringValue;
  color += bandColor * (band * ringValue * 0.7 + halo * mix(0.35, 0.8, high));

  // The shadow: nothing comes back from inside.
  float shadow = 1.0 - smoothstep(0.96, 1.0, radial);
  color *= 1.0 - shadow;

  // A red smoulder leaking up from the horizon as things go wrong.
  float smoulder = exp(-abs(lensedUv.y - horizon) * 9.0) * low * (0.6 + 0.4 * beat);
  color += hsv2rgb(vec3(0.98 + hue, 1.0, 0.6)) * smoulder * (1.0 - shadow);

  // The dark closing in from the edges, with a ragged, slowly crawling rim.
  float rim = valueNoise(uv * 3.0 + time * 0.15) * 0.25;
  float reach = mix(1.6, 0.35, low * decay) + rim;
  float vignette = 1.0 - smoothstep(reach * 0.5, reach, length(uv * vec2(1.0, 1.3)));
  color *= mix(1.0, vignette, 0.3 + 0.7 * low);

  // Breathing when high, a thud when low. Grain rises with the dread.
  color *= 1.0 + throb;
  float grain = (hash2(gl_FragCoord.xy + fract(time) * 100.0) - 0.5) * 0.12 * low * low;
  color += grain * (1.0 - shadow);

  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
