// Distance from camera
//
// Ported from distanceFromCamera.glsl, which shades a 3D scene by each point's distance from the
// camera's plane, given as the plane equation a, b, c and d. A flat frame has no depth to measure,
// so the frame itself is the scene: every pixel sits at z = 0, and the plane turns over time. What
// is left is a grey ramp that sweeps round, black on the far side of the plane.

uniform float time;
uniform vec2 resolution;

uniform float unitLength;  // @range(0.25, 4) @default(1.5)
uniform float offset;      // @range(-1.5, 1.5) @default(0.5)
uniform float turn;        // @range(0, 2) @default(0.4)

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / (min(resolution.x, resolution.y) * 0.5);
  vec3 worldPosition = vec3(uv, 0.0);

  float angle = time * turn;
  vec4 plane = vec4(cos(angle), sin(angle), 0.0, offset);

  float depth = dot(plane.xyz, worldPosition) + plane.w;
  vec3 color = vec3(depth / unitLength);

  gl_FragColor = vec4(color, 1.0);
}
