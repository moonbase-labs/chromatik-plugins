# S1 to S4. The Shader plugin

What was built after [S0](S0-context-spike.md) settled how a Chromatik package gets an OpenGL
context. One document rather than four, because each stage is small and they only make sense read
together.

## S1. Skeleton

The shader is a `FrameSource`. That seam already promises a thread of its own for the whole life of
a source, with blocking allowed, which is exactly what a context needs: `open` creates it, `grab`
renders and reads back, `close` tears it down, all on one thread, and the engine never touches GL.
Nothing in `chromatik-core` had to change, and `FramePipeline`, `ProjectionControls`,
`ProjectionParams` and `WorkingResolution` are all reused as they were.

The render target is a square framebuffer sized by the existing `WorkingResolution`. Geometry is a
single triangle big enough to cover it, generated from `gl_VertexID`, so there is no vertex buffer
to own. Rows come back from `glReadPixels` bottom-first and go into the frame top-first, because
that is what the projection stage samples.

The clock is driven by the engine rather than read off the wall. That is what lets `Speed` scale it
and `Play` hold it without the renderer knowing either control exists.

**Verified:** rendered onto a 900-point grid through the real pipeline, 59 distinct frames in a
second of engine time, and the projected output matched the rendered frame's orientation.

## S2. Loading

Shaders worth running were written for somewhere else, so the loader wraps rather than rewrites.
A prologue supplies a version header, aliases `gl_FragColor` to a core-profile output, aliases
`texture2D`, and declares any built-in uniform the file did not declare itself. A `#line` directive
then puts the numbering back, so the driver names the line the author is looking at.

Entry points are matched as **definitions**, not as words. `time_ferrets.glsl` carries
`#define mainImage main` left over from being ported by hand, and a substring test reads that as a
Shadertoy shader and appends a call to what is really `main`, which recurses. `party_blob.glsl`
carries a commented-out `mainImage` signature that would fool the same test. Both are in the
regression corpus.

Uniforms are read from the source text rather than from the linked program. Asking the program
would be more authoritative but can only be done on the render thread after a successful compile,
and the controls have to exist before either, with no GPU in the picture: opening a project has to
rebuild its knobs and land saved values on them. The cost is that a uniform the compiler optimises
away still gets a knob, which is the better failure.

The file is watched. An edit has to hold still across two checks a quarter-second apart before it
is read, because editors do not write atomically and half a file is not a syntax error worth
reporting. A save that will not compile leaves the previous program running and reports why.

**Verified:** all five demo shaders compiled and rendered; a broken save reported `ERROR: 0:2`,
the author's own line, and the previous shader kept rendering; an edit made in an editor was picked
up with nothing told to Chromatik.

## S3. Uniforms and the panel

Each declared uniform becomes a control named after it. The knob holds a 0 to 1 position and the
declared `@range` is applied on the way to the shader, so widening a range in the file leaves the
knob where it was rather than making it jump.

Uniforms take the knobs from the left, ahead of `Speed` and `Level`, capped at six so those two
always reach a surface. A `bool` becomes a switch and is pushed past the continuous controls
wherever it was declared, since a switch inside the first eight costs a knob outright.

The device panel is the pattern's own, because Chromatik's default one cannot do either half of
this: it walks the parameter list exactly once and never listens for more, so a knob created when a
shader loads would never appear, and it draws only numbers, switches and dropdowns, so a compiler
error would have nowhere to go but the log. A component that is itself a `UIDeviceControls` is
checked before the plugin registry, so this needs no `LXPlugin` and therefore no licence tier.

`load` is overridden to build the controls from the file on disk **before** `super.load` applies the
saved values, and to put the remote controls back **after**, since a device serialises its own
remote-control list and restoring it would otherwise clobber the one just built.

### Three bugs, all found by driving the running app

The headless harnesses passed while all three of these were live. They are the argument for
verifying in the app.

1. **`removeParameter` disposes by default.** That empties a parameter's listener list while
   Chromatik's performance panel still holds a `UIKnob` on it, and its next rebuild throws once per
   knob, out of `run()` and onto the engine thread. Parameters are unregistered without being
   disposed instead.
2. **A publication race.** The load counter the engine polls was incremented at the top of the load,
   before the uniforms it announces were written. The engine could see a new load, read the previous
   load's uniforms, and never look again, because from its side that load was already dealt with.
   Setting a shader lost its knobs perhaps half the time. It is written last now, which is also what
   publishes the ordinary fields beside it.
3. **Rebuilding controls that had not changed.** Opening a project asks for them twice, once from
   `load` and once when the render thread reports the same file compiled. Tearing identical controls
   down and rebuilding them costs every knob a panel has drawn, and not every panel survives having
   a parameter swapped underneath it mid-rebuild. A rebuild is now skipped when the declarations
   match what is already standing.

**Verified in-app over MCP:** setting a file produced its uniforms at exactly their annotated
default positions; switching shaders swapped the control set; a knob visibly changed the render;
`Play` froze the clock to zero measured change; a hot reload swapped both the render and the
controls; a broken save left the previous shader rendering. Zero exceptions in the log across all
of it.

## S4. Linux, and the release gate

GLX onto a one-pixel pbuffer, because GLX will not make a context current without a drawable and
nothing ever looks at this one. The X display comes from GLFW rather than a second connection of our
own, which Chromatik has already opened; a Wayland session has none to hand over and that is
reported rather than guessed at.

**Not verified on hardware.** Written from the specification and compiled against the real bindings,
never run on a Linux GPU.

The release gate learns the decode-stack trap in its LWJGL form: `lwjgl-opengl` asks for LWJGL core
at compile scope, and leaving that alone bundles a second copy of classes Chromatik has already
loaded. Checked for every plugin jar, and confirmed to fail a jar built to trip it. The shader jar
also gets a size ceiling of its own, since it legitimately carries about a megabyte of GL bindings
that the 1 MB plugin limit would reject.

## What is not done

**Windows.** It needs a real window to hang a device context on, and there is no way around that:
the desktop DC will not take a pixel format, and a memory DC gets the software rasteriser, which
stops well short of a 3.3 core profile. Through LWJGL's bindings a window means a registered window
class, a native window procedure and a hand-assembled struct, none of which can be run or tested
from here. A guessed implementation fails on someone else's machine rather than on ours, so the
platform switch reports it unsupported and the other three are unaffected.

## How it landed, against the plan

- The plan expected `lwjgl-opengl` might be pure Java, which would have saved the four `dist-*`
  profiles. It carries a native, so the shader plugin ships one jar per platform like the core.
- The plan treated the custom device panel as one of two options. It is not optional: without it
  dynamically created knobs never appear at all.
- The plan said demo shaders would keep their maths byte-identical. Two of them could not: `nebula`
  rendered pure black, then pure white, on a core profile. See the decisions log.
