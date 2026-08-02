// Party blob
//
// Copied directly from
// https://github.com/genekogan/Processing-Shader-Examples/blob/master/ColorShaders/data/blobby.glsl
// Comments are my interpretation of what it's doing: calculate the distance r from a distorted
// ray-trace of gl_FragCoord, then use this to colour the frag.
//
// The same shader as blobby.glsl, spelled out and coloured through HSV instead of by hand.

uniform float time;
uniform vec2 resolution;

// Ranges are the span that stays worth looking at. At rate 0 the bounce stops and the frame goes
// black; past depth 4 the value term saturates and floods to a single colour.
uniform float depth;  // @range(0.5, 4) @default(2)
uniform float rate;   // @range(0.25, 2) @default(1)

// number of bouncing iterations
#define N 10
#define PI 3.14159265
// the hue value things tend towards
#define huecenter 0.5

/**
 * Stolen from https://www.shadertoy.com/view/lsS3Wc
 */
vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

void main(void) {
  // a vector that moves around with each iteration, initially normalized
  // relative to center of resolution.
  vec2 v = (gl_FragCoord.xy - (resolution.xy * 0.5)) / min(resolution.x, resolution.y) * 10.0;
  // time-based var used to bounce v around
  float t = time * 0.3;
  // the cumulative sum of each v, used to bounce v around
  float r = 2.0;
  // d some multiple of pi that gets bigger with i, used to bounce v around
  float d = 0.0;

  for (int i = 1; i < N; i++) {
    // bounce v around
    d = (PI / float(N)) * (float(i) * 14.0);
    r += length(vec2(rate * v.y, rate * v.x)) + 1.21;
    v = vec2(v.x + cos(v.y + cos(r) + d) + cos(t), v.y - sin(v.x + cos(r) + d) + sin(t));
  }

  // normalise r in [0,1]
  r = (sin(r * 0.09) * 0.5) + 0.5;
  // make r tend toward 0 with greater depth
  r = pow(r, depth);

  vec3 hsv = vec3(
      // phase shift r around [0,1] by huecenter
      mod(r + huecenter, 1.0),
      1.0 - 0.5 * pow(max(r, 0.0) * 1.2, 0.5),
      1.0 - 0.2 * pow(max(r, 0.4) * 2.2, 6.0));

  gl_FragColor = vec4(hsv2rgb(hsv), 1.0);
}
