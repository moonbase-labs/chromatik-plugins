# 🎛️ Parameters

Chromatik generates each panel from these automatically. They are listed in panel order, which is the order the panel reads left to right, top to bottom. Video, Screen Capture and Shader share the projection controls and differ in everything else.

## Video

| Parameter | Type | Default | Range | Description |
|---|---|---|---|---|
| `Level` | Compound | `1` | 0 to 1 | Master brightness |
| `Speed` | Compound | `1` | 0.1 to 4 | Playback rate. Affects the playhead only, never the decode rate |
| `Scale` | Compound | `1` | 0.1 to 10 | Zoom, larger values zoom in |
| `ScrollX` `ScrollY` | Compound | `0` | -1 to 1 | UV offset, animate for a pan |
| `Yaw` | Compound | `0` | -180 to 180 | Rotation about the vertical axis |
| `Roll` | Compound | `0` | -180 to 180 | Rotation about the view axis |
| `Gamma` | Compound | `1` | 1 to 3 | Pulls mid-tones down. Around 2.2 undoes video's own brightness curve, which is what an LED usually wants |
| `XScale` `YScale` | Compound | `1` | 0.1 to 10 | Per-axis stretch on top of `Scale` |
| `Pitch` | Compound | `0` | -180 to 180 | Rotation about the horizontal axis |
| `TransX` `TransY` `TransZ` | Compound | `0` | -1 to 1 | Shift the image on each axis |
| `Wrap` | Enum | `CLAMP` | `CLAMP` `CLIP` `TILE` `MIRROR` | Sampling behaviour outside the image |
| `Background` | Enum | `BLACK` | `BLACK` `CLEAR` | Colour for points rejected by `CLIP` |
| `Interp` | Enum | `BILINEAR` | `NEAREST` `BILINEAR` | `NEAREST` is blocky, `BILINEAR` is smoother |
| `Position` | Compound | `0` | 0 to 1 | Playhead. Follows playback, and seeks when you drag it |
| `Play` | Boolean | `on` | | Run the playhead |
| `Loop` | Boolean | `on` | | Start again on reaching the end |
| `Restart` | Trigger | | | Jump back to the start and play |
| `Res` | Discrete | `Auto` | `128` `256` `384` `512` `Auto` | Longest edge to decode frames at. `Auto` follows the model's point count. Never enlarges a smaller source |
| `Browse` | Trigger | | | Pick a video with the native file chooser |
| `Reload` | Trigger | | | Re-open the current file |
| `File` | String | empty | | Absolute path, or relative to `~/Chromatik`. Written by `Browse` and saved with the project, no control of its own |

Every `Compound` parameter is modulatable, so any of them can be driven by an LFO, an envelope, or MIDI.

## Screen Capture

The same projection controls, minus everything that needs a timeline. `Speed`, `Position`, `Play`, `Loop` and `Restart` are absent because a live desktop has no playhead to move, and the file controls are absent because there is no file.

| Parameter | Type | Default | Range | Description |
|---|---|---|---|---|
| `Level` | Compound | `1` | 0 to 1 | Master brightness |
| `Scale` | Compound | `1` | 0.1 to 10 | Zoom, larger values zoom in |
| `ScrollX` `ScrollY` | Compound | `0` | -1 to 1 | UV offset. With `Scale`, this is how you crop into part of the desktop |
| `Yaw` `Roll` | Compound | `0` | -180 to 180 | Rotation about the vertical and the view axis |
| `Gamma` | Compound | `1` | 1 to 3 | Pulls mid-tones down. Around 2.2 undoes the display's own brightness curve |
| `XScale` | Compound | `1` | 0.1 to 10 | Horizontal stretch on top of `Scale`. The one to reach for fitting a 16:10 desktop |
| `Freeze` | Boolean | `off` | | Hold the current frame instead of following the screen |
| `YScale` | Compound | `1` | 0.1 to 10 | Vertical stretch on top of `Scale` |
| `Pitch` | Compound | `0` | -180 to 180 | Rotation about the horizontal axis |
| `TransX` `TransY` `TransZ` | Compound | `0` | -1 to 1 | Shift the image on each axis |
| `Wrap` | Enum | `CLAMP` | `CLAMP` `CLIP` `TILE` `MIRROR` | Sampling behaviour outside the image |
| `Background` | Enum | `BLACK` | `BLACK` `CLEAR` | Colour for points rejected by `CLIP` |
| `Interp` | Enum | `BILINEAR` | `NEAREST` `BILINEAR` | `NEAREST` is blocky, `BILINEAR` is smoother |
| `Screen` | Discrete | `0` | 0 to 3 | Which display to capture. Ignored on Windows, which captures the whole desktop |
| `Cursor` | Boolean | `on` | | Include the mouse pointer |
| `Res` | Discrete | `Auto` | `128` `256` `384` `512` `Auto` | Longest edge to capture at. `Auto` follows the model's point count. Never enlarges |

There is no capture-rate control: the capture runs at the engine's own frame rate, capped at 60, since there is nothing to gain from grabbing the screen faster than the renderer consumes it.

`Screen`, `Cursor` and `Res` are read when the capture device opens, so changing any of them reopens it. The engine frame rate is not watched the same way, because it is a slider and reopening per drag increment would stall the capture for the length of the drag; a new rate applies the next time the device opens, which includes switching the pattern off and on.

## Shader

The projection controls again, plus a clock, plus whatever the shader itself declares.

| Parameter | Type | Default | Range | Description |
|---|---|---|---|---|
| *(the shader's uniforms)* | Compound / Boolean | *per file* | 0 to 1 | One control per `uniform` the file declares, named after it. See below |
| `Speed` | Compound | `1` | 0 to 4 | How fast the shader's clock advances. `0` holds it still |
| `Level` | Compound | `1` | 0 to 1 | Master brightness |
| `Play` | Boolean | `on` | | Advance the shader's clock |
| `Scale` `ScrollX` `ScrollY` `Yaw` `Roll` `Gamma` `XScale` `YScale` `Pitch` `TransX/Y/Z` `Wrap` `Background` `Interp` | | | | Exactly as on the other two patterns |
| `Res` | Discrete | `Auto` | `128` `256` `384` `512` `Auto` | Edge length to render at. `Auto` follows the model's point count |
| `Shader` | Discrete | *the current file* | the files in `~/Chromatik/LaserphileShader/` | Pick a shader from the folder. Rescanned each time the menu opens. A file from elsewhere, or none, shows as a first entry |
| `Browse` | Trigger | | | Pick a `.glsl` file from anywhere. Opens in `~/Chromatik/LaserphileShader/` |
| `Reload` | Trigger | | | Read and compile the file again |
| `File` | String | empty | | The chosen path. Saved with the project, relative to `~/Chromatik` where possible |
| `Error` | String | empty | | What the compiler said about the last file that would not build |

Rendering happens on a GPU, in an offscreen context the pattern creates for itself, on the same background thread the video decoder would use. The clock is driven by the engine rather than read off the wall, which is what lets `Speed` scale it and `Play` hold it without the renderer knowing either control exists.

### Uniforms become controls

Every `uniform` the file declares becomes a control named after it, except the ones the plugin drives itself: `time`, `resolution`, and their Shadertoy spellings `iTime`, `iResolution` and `iFrame`.

A knob holds a 0 to 1 position, and the range is applied on the way to the shader. Unannotated, that range is 0 to 1. A trailing comment says otherwise:

```glsl
uniform float depth;  // @range(0.5, 4) @default(2)
uniform float rate;   // @range(0.25, 2) @default(1)
uniform bool  sharp;  // @default(0)
```

Storing the position rather than the value is what makes widening a `@range` non-destructive: the knob stays where it was instead of jumping. A `bool` becomes a switch, `float` and `int` become knobs, and anything larger, a `vec` or a `mat`, gets no control, because there is no sensible way for one knob to hold it.

Getting the defaults right matters more than it sounds. Left at zero, `monjori` divides by one of them and three of the six shipped shaders render a flat frame.

### Which dialects load

The file is never rewritten, only surrounded, and a `#line` directive puts the numbering back so a compile error names the line you are looking at.

| The file has | What happens |
|---|---|
| No `#version` | One is injected |
| `gl_FragColor` | Aliased to a core-profile output |
| `texture2D` / `textureCube` | Aliased to `texture` |
| `void mainImage(out vec4, in vec2)` and no `main` | Wrapped in the `main` Shadertoy would have supplied |
| Its own `#version` | Kept, with the prologue slotted in behind it |
| Neither entry point | Rejected, saying so. A vertex shader looks like this |

Entry points are matched as definitions rather than as words, so a file carrying `#define mainImage main` left over from a hand port is not mistaken for a Shadertoy shader and made to call itself.

### Editing while it runs

The chosen file is watched. Save it and it recompiles, with no button to press. A save that will not compile leaves the previous shader running and puts the compiler's message in `Error`: a typo costs the edit, not the output.

An edit has to hold still across two checks a quarter-second apart before it is read, because editors do not write files atomically and half a file is not a syntax error worth reporting.

## Knob order on a control surface

A MIDI surface binds its eight device knobs to the first eight of the pattern's remote controls, and an APC40 has no way to page past the eighth. So those eight are all continuous, and they are the first eight controls in the panel, in the same order:

| Knob | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| **Video** | `Level` | `Speed` | `Scale` | `ScrollX` | `ScrollY` | `Yaw` | `Roll` | `Gamma` |
| **Screen Capture** | `Level` | `Scale` | `ScrollX` | `ScrollY` | `Yaw` | `Roll` | `Gamma` | `XScale` |
| **Shader** | *uniform 1* | *…* | *up to 6* | `Speed` | `Level` | `Scale` | `ScrollX` | `ScrollY` |

Video and Screen Capture line up except at knob 2, where Screen Capture has no `Speed` to spend the slot on, so everything shifts up one and `XScale` reaches the row.

Shader is the one that breaks the pattern, deliberately. What is worth playing on a shader is the shader, so its own uniforms take the knobs from the left, and `Speed` and `Level` follow them. The row above shows a shader declaring six or more; one declaring two puts `Speed` on knob 3 and `Level` on knob 4. Six is the cap, so that `Speed` and `Level` always reach a knob no matter how much a file declares. A `bool` uniform becomes a switch and is pushed past the continuous ones wherever it was declared, since a switch inside the first eight costs a knob outright.

Because the set changes with the file, Shader is also the only pattern here whose knob layout is not fixed. Its device panel is its own for the same reason: Chromatik's default one reads a device's controls once and never looks again, so a knob created when a shader loads would never appear.

`Pitch` is the rotation left off the knobs. A projection sweeps it least of the three, and the freed slot goes to `Gamma`, which is what you reach for once the video is on real LEDs. `Pitch` is still on the panel and still mappable by hand.

The surface order carries on down the panel from there, so the *n*th control you read is the *n*th a surface sees.

Controls held back from the surface entirely sit at the end of the panel, which is what keeps everything ahead of them lined up. On Video that is `Res`, `Browse`, `Reload` and `File`. On Screen Capture it is `Screen`, `Cursor` and `Res`. On Shader it is `Res`, `Shader`, `Browse`, `Reload`, `File` and `Error`. `Shader`, `Browse`, `Reload`, `File` and `Error` go to disk or come back from it; the rest tear down the current source and open another, and both a screen device and a GL context take real time to build, so a swept knob would thrash them.
