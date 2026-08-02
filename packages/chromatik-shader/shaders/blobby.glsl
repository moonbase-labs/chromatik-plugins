// Blobby
//
// Copied directly from
// https://github.com/genekogan/Processing-Shader-Examples/blob/master/ColorShaders/data/blobby.glsl
//
// A ray is bounced around N times, and how far it travelled decides the colour. The terse
// original; party_blob.glsl is the same idea with the working written out.

uniform float time;
uniform vec2 resolution;

// Ranges are the span that stays worth looking at. Below 0.25 the bounce stops distorting and the
// frame goes flat; much past 1.5 the detail is finer than the grid and turns to speckle.
uniform float depth;  // @range(0.5, 6) @default(2)
uniform float rate;   // @range(0.5, 1.5) @default(1)

#define N 16

void main(void) {
  vec2 v = (gl_FragCoord.xy - (resolution * 0.5)) / min(resolution.y, resolution.x) * 10.0;
  float t = time * 0.3, r = 2.0;

  for (int i = 1; i < N; i++) {
    float d = (3.14159265 / float(N)) * (float(i) * 14.0);
    r += length(vec2(rate * v.y, rate * v.x)) + 1.21;
    v = vec2(v.x + cos(v.y + cos(r) + d) + cos(t), v.y - sin(v.x + cos(r) + d) + sin(t));
  }

  r = (sin(r * 0.09) * 0.5) + 0.5;
  r = pow(r, depth);

  gl_FragColor = vec4(
      r,
      pow(max(r - 0.55, 0.0) * 2.2, 2.0),
      pow(max(r - 4.875, 0.1) * 3.0, 6.0),
      1.0);
}
