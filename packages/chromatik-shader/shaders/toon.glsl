// Toon
//
// Ported from Processing's Toon shading example (ToonFrag.glsl and ToonVert.glsl), which shades a
// lit sphere mesh in four flat bands. There is no mesh here, only a flat frame, so each pixel
// inside the circle works out the sphere's surface normal itself. The light circles the sphere
// over time, standing in for the example's mouse-driven light.

uniform float time;
uniform vec2 resolution;

// fraction is the original's one control: above 1 the bright bands shrink to a highlight, below 1
// they spread until the sphere is nearly all white.
uniform float fraction;    // @range(0.25, 4) @default(1)
uniform float radius;      // @range(0.2, 1.5) @default(0.8)
uniform float orbit;       // @range(0, 3) @default(0.6)
uniform float hue;         // @range(0, 1) @default(0.08)
uniform float saturation;  // @range(0, 1) @default(0)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

void main(void) {
  vec2 uv = (gl_FragCoord.xy - resolution * 0.5) / (min(resolution.x, resolution.y) * 0.5);
  vec2 onSphere = uv / radius;
  float distanceSquared = dot(onSphere, onSphere);

  if (distanceSquared > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }

  vec3 normal = vec3(onSphere, sqrt(1.0 - distanceSquared));
  float angle = time * orbit;
  vec3 lightDirection = normalize(vec3(cos(angle), sin(angle), 1.0));
  float intensity = max(0.0, dot(lightDirection, normal));

  vec4 color;
  if (intensity > pow(0.95, fraction)) {
    color = vec4(vec3(1.0), 1.0);
  } else if (intensity > pow(0.5, fraction)) {
    color = vec4(vec3(0.6), 1.0);
  } else if (intensity > pow(0.25, fraction)) {
    color = vec4(vec3(0.4), 1.0);
  } else {
    color = vec4(vec3(0.2), 1.0);
  }

  vec4 surfaceColor = vec4(hsv2rgb(vec3(hue, saturation, 1.0)), 1.0);

  gl_FragColor = color * surfaceColor;
}
