// Poles
//
// Domain colouring of a rational function: hue is the argument of f(z), rings its modulus. The
// zeros and poles each orbit the origin at their own rate, and because the colour wheel winds
// once round each zero and the other way round each pole, the frame fills with pinwheels that
// spin as the points pass each other. Far out, f(z) settles into one wheel wound (zeros minus
// poles) times, so the edges of the frame stay as coloured as the middle.

uniform float time;
uniform vec2 resolution;

uniform float zoom;    // @range(0.5, 4) @default(1.4)
uniform float orbit;   // @range(0, 1.5) @default(0.7)
uniform float speed;   // @range(0, 2) @default(0.4)
uniform float rings;   // @range(0, 4) @default(1.5)
uniform float hue;     // @range(0, 1) @default(0)
uniform float zeros;   // @range(1, 4) @default(3)
uniform float poles;   // @range(0, 3) @default(2)

vec3 hsv2rgb(in vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

vec2 complexMultiply(vec2 a, vec2 b) {
  return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x);
}

vec2 complexDivide(vec2 a, vec2 b) {
  float denominator = max(dot(b, b), 1e-6);
  return vec2(a.x * b.x + a.y * b.y, a.y * b.x - a.x * b.y) / denominator;
}

vec2 orbiting(float index, float rate, float phase, float radius) {
  float angle = time * speed * rate + phase + index * 2.0943951;
  return radius * vec2(cos(angle), sin(angle));
}

void main(void) {
  vec2 z = (gl_FragCoord.xy - resolution * 0.5) / min(resolution.x, resolution.y) * 2.0 * zoom;
  vec2 value = vec2(1.0, 0.0);
  int zeroCount = int(zeros + 0.5);
  int poleCount = int(poles + 0.5);

  for (int index = 0; index < 4; index++) {
    if (index < zeroCount) {
      value = complexMultiply(value, z - orbiting(float(index), 1.0 + 0.3 * float(index), 0.0, orbit));
    }
  }

  for (int index = 0; index < 3; index++) {
    if (index < poleCount) {
      value = complexDivide(value, z - orbiting(float(index), -0.7 - 0.25 * float(index), 1.0, orbit * 0.55));
    }
  }

  float argument = atan(value.y, value.x) / 6.2831853;
  float modulus = log(max(length(value), 1e-5));
  float band = fract(modulus * rings);
  float brightness = 0.6 + 0.4 * band;

  gl_FragColor = vec4(hsv2rgb(vec3(argument + hue + time * 0.03, 0.95, brightness)), 1.0);
}
