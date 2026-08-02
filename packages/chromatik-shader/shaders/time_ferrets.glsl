// Time ferrets
//
// A radial sweep: polar coordinates, then a sawtooth around the angle whose slope is pushed
// about by the radius and the clock. Reads as spiral arms winding in and out.

uniform float time;
uniform vec2 resolution;

uniform float depth;  // @range(0, 20) @default(6)
uniform float rate;   // @range(0, 2)  @default(0.7)
// Was a #define switched off in the original. It is a control now, because a hard edge is what
// reads at a distance on real fixtures and a gradient is what reads on a screen.
uniform bool sharp;   // @default(0)

#define tau 6.283185307

void main(void) {
  // Normalized pixel coordinates, centred and square regardless of aspect
  float m = max(resolution.x, resolution.y);
  vec2 uv = 0.5 * (gl_FragCoord.xy - (0.5 * resolution.xy)) / m;

  // rt is (r, theta) but both normalized [0,1]
  vec2 rt = vec2(length(uv), atan(uv.y, uv.x) / tau + 0.5);

  float a = depth * cos(time * rate * rate);
  float c = sin(time);
  float d = mod((2.0 * rt.y) + rt.x * a + c, 1.0);

  if (sharp) {
    d = step(0.5, d);
  }

  gl_FragColor = vec4(vec3(d), 1.);
}
