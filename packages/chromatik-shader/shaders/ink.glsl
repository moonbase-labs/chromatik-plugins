// Ink
//
// Fractal noise fed through itself, after Inigo Quilez's domain warping: the point each layer
// samples is pushed about by the layer before, so the field flows like ink dropped in water
// rather than rippling like a texture. Colour follows the two warp layers, so streaks of one hue
// thread through another instead of the whole frame fading together.

uniform float time;
uniform vec2 resolution;

uniform float warp;      // @range(0, 4) @default(1.8)
uniform float zoom;      // @range(0.5, 5) @default(1.6)
uniform float flow;      // @range(0, 1) @default(0.25)
uniform float hue;       // @range(0, 1) @default(0.62)
uniform float spread;    // @range(0, 1) @default(0.4)
uniform float contrast;  // @range(0.5, 3) @default(1.6)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

vec2 gradientAt(vec2 cell) {
  float angle = 6.2831853 * fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
  return vec2(cos(angle), sin(angle));
}

float gradientNoise(vec2 point) {
  vec2 cell = floor(point);
  vec2 local = fract(point);
  vec2 fade = local * local * (3.0 - 2.0 * local);

  float bottomLeft = dot(gradientAt(cell), local);
  float bottomRight = dot(gradientAt(cell + vec2(1.0, 0.0)), local - vec2(1.0, 0.0));
  float topLeft = dot(gradientAt(cell + vec2(0.0, 1.0)), local - vec2(0.0, 1.0));
  float topRight = dot(gradientAt(cell + vec2(1.0, 1.0)), local - vec2(1.0, 1.0));

  return mix(mix(bottomLeft, bottomRight, fade.x), mix(topLeft, topRight, fade.x), fade.y);
}

float fbm(vec2 point) {
  float sum = 0.0;
  float amplitude = 0.5;
  mat2 twist = mat2(0.8, 0.6, -0.6, 0.8) * 2.02;

  for (int octave = 0; octave < 5; octave++) {
    sum += amplitude * gradientNoise(point);
    point = twist * point + vec2(1.7, 9.2);
    amplitude *= 0.5;
  }

  return sum;
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * zoom;
  float drift = time * flow;

  vec2 first = vec2(
      fbm(uv + vec2(0.0, drift)),
      fbm(uv + vec2(5.2, 1.3) - vec2(drift * 0.7, 0.0)));
  vec2 second = vec2(
      fbm(uv + warp * first + vec2(1.7, 9.2) + vec2(0.0, drift * 0.3)),
      fbm(uv + warp * first + vec2(8.3, 2.8) - vec2(drift * 0.2, 0.0)));
  float field = fbm(uv + warp * second);

  float shade = clamp(0.5 + contrast * field, 0.0, 1.0);
  float localHue = hue + spread * (first.x + second.y);
  float saturation = 0.95 - 0.55 * shade * shade;
  float value = 0.1 + 0.9 * smoothstep(0.1, 0.9, shade);

  gl_FragColor = vec4(hsv2rgb(vec3(localHue, saturation, value)), 1.0);
}
