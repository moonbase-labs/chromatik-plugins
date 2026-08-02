// Julia bloom
//
// A Julia set that falls into itself and never lands, on a rig rather than a screen.
//
// Three ideas, each of which is here because the obvious version of it failed on a model of a few
// thousand points.
//
// Brightness comes from how far a point is from the set, worked out from the orbit and its
// derivative, and then divided by the width of the frame. Being a share of the frame rather than a
// length is the whole trick: a filament asked for that way is the same width on the rig no matter
// how far in the dive has gone. Anything measured out where the orbit travels instead, an escape
// count or a trap distance, spreads and flattens as the frame tightens, because those numbers know
// nothing about how tight the frame is.
//
// Colour comes from the orbit's tour: where it passed the traps, which pass came closest, and how
// long it took to leave. That is a fingerprint of where a point started, so it separates one
// filament from the next, which distance from the set cannot do because every filament is at
// distance zero.
//
// The dive is endless because it is aimed at the one place a set of this family repeats itself.
// Details below, at the fixed point.
//
// Two ranges of zoom, and they behave differently on purpose. Wide holds the whole set and the dive
// stands down, because the whole set is not self-similar and pretending otherwise pumps. Settled sits
// on the fixed point and dives forever. The knob crosses between them and nothing in between pumps.
//
// The constant c walks the edge of the Mandelbrot set rather than sitting still, so the shape itself
// is the animation and it never repeats and never jumps.

uniform float time;
uniform vec2 resolution;

// Knob order is deliberate: a control surface binds only the first six, so those are the ones a
// hand reaches for mid-show. Where the camera is and what colour it comes out win that space; what
// the fractal is made of is set once and left, and lands on the panel below. Ranges are the span
// that stays worth looking at, not the span that compiles.
uniform float dive;   // @range(-1, 1) @default(0.15)
uniform float zoom;   // @range(0.5, 8) @default(3)
uniform float panX;   // @range(-1, 1) @default(0)
uniform float panY;   // @range(-1, 1) @default(0)
uniform float hue;    // @range(0, 1) @default(0.55)
uniform float cycle;  // @range(-0.3, 0.3) @default(0.02)
uniform float morph;  // @range(0.85, 1.08) @default(0.97)
uniform float rings;  // @range(0, 1) @default(0.35)
uniform float focus;  // @range(4, 120) @default(22)
uniform float depth;  // @range(12, 160) @default(48)

const float TAU = 6.28318530718;

// Loops need a bound the compiler can see, so the real iteration count is a break inside a loop
// this long. It matches the top of depth's range: raising one without the other silently caps the
// knob partway along its travel.
const int MAX_ITERATIONS = 160;

// Escape is tested past the usual radius of 2. An orbit that leaves slowly is what draws the halo
// around the set, and stopping it the instant it crosses 2 cuts that halo off mid-sweep. Not much
// past it though: every extra step an already-departing orbit takes is another chance to graze a
// trap, and those grazes land far outside the set where they read as haze rather than structure.
const float ESCAPE_SQUARED = 64.0;

// Stands in for "has not come near anything yet", before the first pass has had its say. Large
// enough to lose every comparison it is put in, small enough to stay an ordinary float.
const float FAR = 1e4;

// How wide the frame is in the complex plane before zoom is applied. Enough to hold the whole set
// with margin, since the set never grows past a radius of about 1.5 over morph's range.
const float BASE_HALF_WIDTH = 1.8;

// The span of zoom over which the frame stops framing the whole set and settles onto the point the
// dive scales about. See where they are used.
const float FRAMING_WIDE = 1.0;
const float FRAMING_SETTLED = 3.0;

// How much of the palette the trap distance sweeps through. See where it is used.
const float HUE_SPREAD = 0.25;

// How much of it the escape count sweeps through. This is what colours the space outside the set,
// where every point escaped and the only thing separating one from the next is how long it took, so
// it wants a wide share to itself. It draws bands, because an escape count is a whole number, and
// here that is wanted: they follow the edge of the set exactly and read as contours around it.
const float ESCAPE_HUE = 0.6;

// How far along the palette each further pass of the iteration moves.
//
// Small on purpose. Which pass caught a point is not smooth from one point to the next: right up
// against the set it can differ between neighbours, and at a wide spacing those neighbours come out
// on opposite sides of the palette, which is a confetti of every hue at once rather than a picture.
// A full turn spread over forty passes keeps neighbours near each other on the palette, so the
// colour follows the structure instead of fighting it, and the frame still holds the whole palette
// because the pass numbers across it range much wider than forty.
const float BAND_SPACING = 0.025;

// Where the orbit derivative is pegged, and the floor it is divided by. See the loop.
const float DERIVATIVE_CEILING = 1e6;
const float SMALLEST_DERIVATIVE = 1e-6;

// The wide second falloff: how much slower it falls than the core, and how bright it starts. Wide
// enough to be read as a glow around the set, not so wide that the far corners of the frame are
// still lit by it, which is a haze over everything rather than a shape.
const float HALO_WIDTH = 0.3;
const float HALO_LEVEL = 0.5;

// Inside the set, where the distance estimate is zero everywhere and so has nothing to say.
//
// A flat value, and it is worth knowing why before anyone tries to find some structure to put here.
// Below morph 1 the orbits inside all fall towards the same attracting point and get there in about
// ten passes, so by the end of the iteration every interior point holds an orbit sitting in the same
// place as its neighbours. There is genuinely no fine detail in there: a filled set of this family
// is a solid region, and everything that rewards looking is out on its edge.
//
// Driving it from the orbit trap, which is the obvious thing to reach for, is worse than flat. The
// one place all those orbits end up moves as c walks, so the trap distance the whole interior
// reports moves with it, and the body reads as flickering between lit and unlit for reasons nothing
// on screen explains. Brightness stays put and hue is left to vary, which it still does.
const float INTERIOR_LEVEL = 0.18;

// Applied before the highlights are rolled off, so the filaments land on the part of the curve that
// is still climbing rather than down where everything is dim and nearly the same. Without it the
// brightest point in the frame comes out around 40 percent, which on a rig is a fractal rendered in
// greys.
const float GAIN = 3.0;

// Floor on how much one dive step shrinks the view.
//
// The step size is the fractal's own, not a choice, and it runs all the way down to zero: it is
// smallest where c sits at the cusp of the cardioid, and c walks over that cusp once a lap. Right
// there the set repeats itself only under a scaling by one, which is to say it does not repeat, and
// there is no step to take. The floor stops the divide below it from running away.
const float SLOWEST_DIVE_STEP = 0.05;

// How fast the dive falls, in e-foldings of scale per second at dive fully up.
//
// The dive is counted in these rather than in steps of the fractal's own, and the difference matters
// because the step size varies by a factor of twenty over one lap of c. Counting steps would hand
// the knob a different speed at every moment, crawling near the cusp and racing on the far side,
// with nothing on screen to explain either. Dividing the step size back out below leaves the frame
// closing at the speed the knob asks for wherever c happens to be. What changes instead is how often
// the phase wraps, which is the harmless end of the trade: a small step means a wrap between two
// layers that are nearly the same size, and nobody can see one of those.
const float DIVE_RATE = 0.7;

vec2 rotate(vec2 point, float rads) {
  float cs = cos(rads);
  float sn = sin(rads);
  return point * mat2(cs, -sn, sn, cs);
}

// Complex multiplication. GLSL's own vec2 product is componentwise, which is a different operation
// and a silent one, so every product here goes through this.
vec2 complexMultiply(vec2 a, vec2 b) {
  return vec2((a.x * b.x) - (a.y * b.y), (a.x * b.y) + (a.y * b.x));
}

/**
 * The square root of a complex number, on the branch with a positive real part.
 *
 * Written out rather than reached for, because GLSL's sqrt() is real. The identity is the usual
 * one: the root's components are sqrt((|w| +/- w.x) / 2), and the imaginary part takes the sign of
 * w.y. Both terms are clamped at zero before the real sqrt, since rounding can put a quantity that
 * is mathematically zero a hair below it, and sqrt of a negative is a NaN that would spread into
 * every coordinate downstream.
 */
vec2 complexSqrt(vec2 w) {
  float magnitude = length(w);
  float real = sqrt(max(0.5 * (magnitude + w.x), 0.0));
  float imaginary = sqrt(max(0.5 * (magnitude - w.x), 0.0));

  return vec2(real, (w.y < 0.0) ? -imaginary : imaginary);
}

// Inigo Quilez's cosine palette, on its rainbow settings. Every channel is a full-swing cosine, so
// the colours come out saturated rather than pastel, which is what an LED wants.
vec3 palette(float t) {
  return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

/**
 * One rendering of the set, for a point already placed in the complex plane.
 *
 * Separated out because the dive draws two of these at once, a step apart, and dissolves between
 * them. Everything that decides what the picture looks like is passed in, so the two calls differ
 * only in where they sample.
 */
vec3 juliaLayer(vec2 start, vec2 c, vec2 trapPoint, float trapRadius, int limit, float tint,
    float sharpness) {
  vec2 z = start;

  // How much the iteration so far magnifies a step taken back at the starting point. Carried
  // alongside the orbit because it is what keeps the filaments the same width at any depth.
  vec2 derivative = vec2(1.0, 0.0);

  float nearestPoint = FAR;
  float nearestRing = FAR;

  float pointCaughtAt = 0.0;
  float ringCaughtAt = 0.0;

  // Stays at limit when the orbit never escapes, which is the honest reading: it survived the whole
  // budget.
  int escapedAt = limit;

  for (int i = 0; i < MAX_ITERATIONS; i++) {
    if (i >= limit) {
      break;
    }

    // The chain rule on z -> z*z + c, taken before z moves, since the rule wants the z it was at.
    derivative = 2.0 * complexMultiply(z, derivative);

    // Held at a ceiling rather than let run. It grows geometrically and reaches float infinity part
    // way through a long iteration, after which one more multiply by a z lying on an axis is a NaN
    // that spreads over the rest of the frame. The ceiling is set far above where it stops mattering:
    // the distance below is already reading as flat zero by a derivative of a few thousand, so
    // pegging it at a million changes nothing anyone can see.
    float growth = length(derivative);

    if (growth > DERIVATIVE_CEILING) {
      derivative *= DERIVATIVE_CEILING / growth;
      growth = DERIVATIVE_CEILING;
    }

    z = complexMultiply(z, z) + c;

    // Left as raw distances in the plane the orbit travels through, because these decide hue and
    // hue alone. What they are good at is telling one part of the set from another: an orbit's tour
    // is a fingerprint of where it started, so two neighbouring filaments pass the traps quite
    // differently and end up different colours. What they are bad at is deciding brightness, which
    // the escape distance below does instead.
    float toPoint = length(z - trapPoint);
    float toRing = abs(length(z) - trapRadius);

    // Which pass came closest is kept as well as how close it came, because it is the stronger of
    // the two colour signals. How close an orbit got saturates, since most orbits near the set
    // eventually pass very close to anything, but which pass it happened on stays distinct.
    if (toPoint < nearestPoint) {
      nearestPoint = toPoint;
      pointCaughtAt = float(i);
    }

    if (toRing < nearestRing) {
      nearestRing = toRing;
      ringCaughtAt = float(i);
    }

    if (dot(z, z) > ESCAPE_SQUARED) {
      escapedAt = i;
      break;
    }
  }

  // Blended rather than picked, so sweeping the knob moves the structure from one trap's shape to
  // the other's instead of cutting between two unrelated pictures.
  float nearest = mix(nearestPoint, nearestRing, rings);
  float caughtAt = mix(pointCaughtAt, ringCaughtAt, rings);

  float survival = float(escapedAt) / float(limit);

  // How far this point is from the set itself, and the reason the dive can go as deep as it likes
  // and still have something to look at.
  //
  // This is the standard estimate for a set of this family: how far the orbit finally got, damped by
  // its own logarithm, divided by how much the iteration was magnifying by the time it got there. It
  // comes out as a real distance in the plane, so dividing by the frame width turns it into a share
  // of the frame, and a filament asked for as a share of the frame is the same width on the rig at
  // every depth. Colouring by anything measured out where the orbit went instead, a trap distance or
  // an escape count, gives a picture that spreads and flattens the further in the frame goes,
  // because those quantities know nothing about how tight the frame around the starting point is.
  //
  // Only meaningful for a point that left. One that never did is inside the set, where the distance
  // is zero by definition.
  float glow = INTERIOR_LEVEL;

  if (escapedAt < limit) {
    float reached = length(z);
    float estimate =
      (reached * log(reached)) / max(length(derivative), SMALLEST_DERIVATIVE);

    // Two falloffs off the one distance: a tight core that draws the set as a bright line, and a
    // wide skirt that lets the shape of the space around it show. The core on its own is close to
    // invisible on a rig, because the set really is a curve of no thickness and a curve rendered
    // honestly falls between the points of the model more often than it lands on one. The skirt is
    // what the eye actually reads as the fractal, and because it is a falloff off the same distance
    // it holds its width at any depth exactly as the core does.
    float distanceInFrames = estimate * sharpness;

    glow = exp(-distanceInFrames) + (HALO_LEVEL * exp(-distanceInFrames * HALO_WIDTH));
  }

  // Hue comes off the orbit's tour rather than off its distance from the set, so that colour varies
  // along a filament that brightness holds at a constant width.
  float tone =
    tint + (HUE_SPREAD * nearest) + (ESCAPE_HUE * survival) + (BAND_SPACING * caughtAt);

  // One hue per point rather than a colour per structure added together. Adding two samples of a
  // cosine palette is adding two colours roughly opposite each other on it, and opposite colours sum
  // towards grey, which is how a saturated palette ends up rendering mud.
  return palette(tone) * glow * GAIN;
}

void main(void) {
  // Divided by the shorter edge rather than by each axis, so the set stays round on a frame that is
  // not square instead of being stretched to fit it.
  vec2 uv = (gl_FragCoord.xy - (0.5 * resolution)) / min(resolution.x, resolution.y);

  // Pan is counted in frame widths rather than in complex units, so it is added before the zoom
  // divides. A whole turn of the knob moves by exactly one frame at any zoom, which is what makes
  // it possible to line a deep dive up on something: at high zoom the same turn is a fine nudge.
  vec2 offset = (uv + vec2(panX, panY)) * (2.0 * BASE_HALF_WIDTH) / zoom;

  // Where c is taken from, which decides what shape the set is at all.
  //
  // Walking a plain circle is the usual thing and it is a poor choice here. Whether a set of this
  // family is one connected piece or a scatter of dust is decided entirely by whether c is inside
  // the Mandelbrot set, and a circle of any fixed radius spends most of its way round outside it,
  // where the set is dust and there is very little to look at.
  //
  // This walks the edge of the Mandelbrot set's main body instead, which is a cardioid and has a
  // closed form. Every c on it is a set on the point of coming apart, which is where all the lacy
  // ones are. morph then scales that point towards or away from the origin, so it crosses the edge:
  // below 1 c is inside and the set is connected and filled, above 1 it is outside and the set
  // breaks into dust. The whole knob is the one transition worth having.
  float phi = time * 0.13;
  vec2 cardioid =
    (0.5 * vec2(cos(phi), sin(phi))) - (0.25 * vec2(cos(2.0 * phi), sin(2.0 * phi)));
  vec2 c = morph * cardioid;

  // Where the dive is aimed, and why it can go on forever.
  //
  // Every set of this family has a repelling fixed point at (1 + sqrt(1 - 4c)) / 2, and it always
  // lies on the set rather than inside or outside it, so it is somewhere there is detail at every
  // scale. Near it the set is self-similar under multiplication by the derivative there, which is
  // twice the fixed point. Take one step of that and the picture repeats.
  //
  // That is what keeps this honest. A plain exponential zoom runs out of float after twenty-odd
  // steps and turns to blocks. Here the step is a repeat, so the position within a step is all that
  // is ever needed, the coordinates never leave one step's worth of range, and the zoom has no
  // depth to run out of.
  vec2 fixedPoint = 0.5 * (vec2(1.0, 0.0) + complexSqrt(vec2(1.0, 0.0) - (4.0 * c)));
  vec2 step = 2.0 * fixedPoint;

  float stepLog = max(log(length(step)), SLOWEST_DIVE_STEP);
  float stepTurn = atan(step.y, step.x);

  // How far the frame has committed to the fixed point, which decides both where it is pointed and
  // whether the dive runs at all. Everything below hangs off it.
  float settled = smoothstep(FRAMING_WIDE, FRAMING_SETTLED, zoom);

  // Where the frame sits, which has to be two different places.
  //
  // The dive scales about the fixed point, so a frame centred anywhere else is a frame sliding
  // towards the fixed point as the phase runs, and a frame small enough to have left the fixed point
  // behind is a frame that empties out. That argues for centring on the fixed point. But the fixed
  // point sits out on the edge of the set rather than in the middle of it, so a wide frame centred
  // there hangs the whole picture off one corner. That argues for the origin.
  //
  // Both, then, chosen by zoom: wide frames sit on the origin and hold the set, and as the zoom
  // comes in the centre settles onto the fixed point, which is the only place worth being once the
  // frame is smaller than the set. It reads as flying in towards a point on the edge, which is what
  // it is.
  //
  // Free to do, because the wrap below asks only that the two layers agree on something the phase
  // does not change, and zoom does not change with phase.
  vec2 centre = mix(vec2(0.0), fixedPoint, settled);
  vec2 aim = (offset + centre) - fixedPoint;

  // Only the fraction is used, so this grows without ever being large.
  //
  // Scaled by settled, which is what keeps the wrap honest. The two layers below only match each
  // other where the set repeats itself, and that is near the fixed point, which is where a settled
  // frame is. On a wide frame they are the whole set at two different sizes, and no dissolve hides
  // that: it comes out as a slow zoom in that snaps back out at every wrap. Rather than ship that,
  // the dive eases off as the frame widens. Pulling back gives the whole set held steady, pushing in
  // gives a dive that never ends, and there is no setting in between where it pumps. A wide frame is
  // not left still either, since morph is walking c the whole time.
  float phase = fract((time * dive * settled * DIVE_RATE) / stepLog);

  // Two layers one step apart, dissolving into each other. Self-similarity makes them the same
  // picture, so the far layer arrives already matching the near one as it fades in, and at the
  // moment the phase wraps the pair has swapped places with nothing to see.
  //
  // Note that pan and centre are inside this, not applied after it. Both layers are then the same
  // region under one step of the same scaling, which is the condition for them to match; shifting
  // either one afterwards would slide the layers against each other and put a seam back at every
  // wrap.
  vec2 near = fixedPoint + (exp(-phase * stepLog) * rotate(aim, -phase * stepTurn));
  vec2 far =
    fixedPoint + (exp(-(phase + 1.0) * stepLog) * rotate(aim, -(phase + 1.0) * stepTurn));

  // Two traps: a wandering point and a breathing circle. The point pulls the filaments into knots
  // that follow it around, the circle lays down bands the orbits cross.
  vec2 trapPoint = 0.45 * vec2(cos(time * 0.21), sin(time * 0.17));
  float trapRadius = 1.0 + (0.35 * sin(time * 0.09));

  // Guarded rather than trusted. depth cannot reach zero through its declared range, but a range
  // edited in this file is one character away from making the survival divide a divide by zero, and
  // a NaN there takes the whole frame to black.
  int limit = max(int(depth), 1);

  float tint = hue + (cycle * time);

  // Each layer is told its own frame width, and that is what keeps the pair matching. The filament
  // width is set as a share of the frame, the far layer's frame is one step smaller than the near
  // one's, and the trap distances it measures are smaller by that same step, so the two draw
  // filaments of equal width on the rig. Handing both the same width would draw one of them at the
  // wrong scale and put back the seam this is all built to avoid.
  float nearHalfWidth = (BASE_HALF_WIDTH / zoom) * exp(-phase * stepLog);
  float farHalfWidth = (BASE_HALF_WIDTH / zoom) * exp(-(phase + 1.0) * stepLog);

  vec3 color = mix(
      juliaLayer(near, c, trapPoint, trapRadius, limit, tint, focus / nearHalfWidth),
      juliaLayer(far, c, trapPoint, trapRadius, limit, tint, focus / farHalfWidth),
      phase);

  // Filaments crossing each other add up, and two bright ones overlapping is already past 1. Rolling
  // the highlights off rather than clipping them keeps a crossing readable as a brighter thread
  // instead of a flat white patch, and leaves the faint end untouched.
  color = color / (1.0 + color);

  // Leans the mid tones down rather than up. An LED has a genuinely black off state, so the frame
  // gains more from the gaps between filaments going properly dark than it would from the filaments
  // themselves being a little brighter. Clamped first because a negative base makes pow() undefined
  // in GLSL, and on a core profile that is a NaN that spreads.
  color = pow(clamp(color, 0.0, 1.0), vec3(1.6));

  gl_FragColor = vec4(color, 1.0);
}
