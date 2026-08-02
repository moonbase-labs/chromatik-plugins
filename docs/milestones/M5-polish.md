# M5. Polish

**Goal:** Turn the working plugin into a shippable v1: correct colour on real LEDs, robust under bad input, sensible defaults, and a clean distribution story.

**Prerequisite:** M4 done (or M3 if screen capture is deferred).

## Tasks

- [x] Colour-space and gamma correction: configure swscale (JavaCV) for full-range RGB so video does not render washed-out/dark; tune the `gamma` (1..3) and `level` params against a real LED test. **Landed differently: the range was already right and swscale is not configurable through JavaCV. See below.**
- [x] `workingResolution` parameter: 128/256/384/512/AUTO; AUTO derives from point count (roughly `clamp(2*sqrt(numPoints), 128, native)`).
- [x] ~~Frame buffer pooling: reuse the ring's `int[]` slots to avoid per-frame GC churn.~~ **Dropped.** `workingResolution` caps a frame at 512px on its longest side, roughly 590 KB, which is ordinary young-generation garbage rather than the humongous allocation a 4K frame would be. Performance is good in practice, so the cross-thread recycling was not worth the tearing risk on a hot path.
- [x] Error handling: bad/missing file renders the background mode plus a status; never throws on the engine thread; decode errors are logged and recoverable.
- [x] Lifecycle hardening: confirm no thread/native-handle leaks across activate/deactivate, file change, and source change.
- [x] Audio: confirm the decoder does not decode the audio track (save CPU). **Confirmed, no change needed.**
- [x] Demo `.lxp` with preloaded geometry and a `VideoPattern` for one-click verification.
- [x] Per-OS/arch build profiles: `dist-macos` (both architectures in one jar), `dist-windows`, `dist-linux-x86_64`, `dist-linux-arm64`. Landed early with the release pipeline.
- [x] Distribution: tag-triggered GitHub Release, per-platform verification on real runners, install steps and LGPL notice in the README and the release template.

## Exit criteria

- Video looks correct on real LEDs (not washed out); brightness/gamma tunable.
- No freeze or leak under bad input, activate/deactivate cycling, or source switching.
- A one-click demo project exists.
- Per-platform jar(s) build and install; README covers distribution and licence.

## Files touched

New: `ColorSpaceCorrection.java` (the BT.709 matrix and the rail-holding), `WorkingResolution.java` (Auto sizing and the grabber cap), `projects/demo.lxp`, `projects/demo-bars.mp4`.

Changed: `VideoPattern.java` (`Gamma`, `Res`, `onModelChanged`, background when there is no frame, the missing-file check), `ProjectionParams.java` (the gamma/level table and the shared background colour), `Projector.java` (table lookup in place of the level multiply), `FramePipeline.java` (retryable grabs, the size threaded through to `open`), `FileVideoSource.java` and `ScreenCaptureSource.java` (correction, working resolution, null guards), `FrameSource.java` (`open` takes the size), `packages/chromatik-video/pom.xml` (dependency exclusions and shade filters, in the module rather than the parent because they name the decode stack), `README.md`.

## How it landed (deltas from the plan above)

- **The colour fault was the matrix, not the range.** The plan expected washed-out video from a full-range mix-up. Measured against FFmpeg's own conversion of the same frame, the range is handled correctly: greys and the black-to-white sweep come through within 1 or 2 of 255. What is wrong is the coefficient set. Turning video's brightness-plus-colour-difference channels into RGB needs one set for standard definition and another for high definition, the file says which, and JavaCV never passes that answer to the scaler. Every file is decoded as standard definition, which costs up to 32 of 255 on a strongly coloured pixel and nothing at all on a grey one. It reads as a hue and saturation shift, not as washed out.

- **swscale cannot be configured through JavaCV, so the correction happens after it.** `FFmpegFrameGrabber` only ever calls `sws_getCachedContext`, `sws_scale` and `sws_freeContext`, never `sws_setColorspaceDetails`, and its scaler context is a private field. The scaler's error is a fixed linear mix of the RGB it produced, so `ColorSpaceCorrection` undoes it with another fixed linear mix, `M709 * inverse(M601)`, applied per decoded pixel on the decode thread. Both matrices work on the same gamma-encoded values, so composing them is exact rather than approximate.

- **Channels sitting on 0 or 255 are left alone.** The wrong coefficients drive some channels out of range and the scaler clamps them before we see them, so the information the correction needs is gone. Correcting anyway lifts green off a pure magenta bar and turns it dirty. Holding those rails takes the average error from 3.277 to 1.488; correcting everything reaches 1.235 on clean input but dirties the saturated bars, which is the wrong trade for LEDs.

- **Only an explicit BT.709 tag earns a correction.** A file that says nothing is left as it is: the scaler's assumption is then the same guess anyone would make, and correcting on a guess would damage genuinely standard-definition footage. Screen capture asks the same question and normally answers no, since a capture device hands over RGB with no colour-difference conversion involved.

- **The filtergraph route was tried and rejected.** Driving swscale properly needs the undecoded frame, and JavaCV's `ImageMode.RAW` keeps only the first of `yuv420p`'s three planes: a `Frame` carries one stride and the format needs three. Routing through a packed intermediate does work, but the extra chroma resample lands it at 1.464 against the in-process correction's 0.669 on the same input, and it would add libavfilter to the natives every platform has to load.

- **`gamma` is a control, not a fix, and defaults to 1.** Video spreads brightness the way a screen expects it; an LED answers its drive value far more directly, so the same numbers land too bright through the middle. Gamma bends the middle back down and around 2.2 undoes video's own curve. It defaults to 1 so nothing already saved changes appearance. **The right default is whatever the rig says, and that is still outstanding.**

- **`gamma` and `level` share one 256-entry table**, rebuilt only when either moves, with a flag that short-circuits it while both sit at their defaults. The per-point loop does a lookup instead of a `Math.pow`, which also makes `level` cheaper than the multiply it used to be.

- **`workingResolution` generalises M4's screen-capture cap.** The fixed 480px ceiling on `ScreenCaptureSource` became `WorkingResolution`, shared by both sources. The constraint it was written around is unchanged and is now documented on `FrameSource.open`: the size has to be asked for after the device is open and before the first grab, which is why it is an argument to opening rather than a setting, and why changing it opens the source again. Nothing is ever enlarged.

- **`Res` and `Gamma` sit in different places on the panel.** `Gamma` is on a knob, Video's eighth and Screen Capture's seventh, because a rig wants it by hand the moment its LEDs are lit; `Pitch` is the rotation that gave up the slot. `Res` joins `Screen` and `Cursor` in the tail that is held back from the surface, because like them it tears down the current source and opens another.

- **A decode failure is now survivable.** The catch sat outside the decode loop, so one damaged frame ended playback permanently. Failures are counted and retried, the first of a run is logged, and only ten in a row without a good frame is taken as the source having gone. Live capture gets the same treatment, since a desktop sleeping or changing resolution surfaces as a failed grab.

- **No status field.** The plan called for one on `FramePipeline`. Every failure already logs where it happens, and with no custom device UI the log is the only place a status can go, so it would have been a second route to the same line.

- **The uber-jar trim went deeper than shade filters.** javacv depends on all thirteen of its native bindings at compile scope rather than optionally, so the jar carried OpenCV, OpenBLAS, Tesseract, Leptonica, two Kinect drivers, two RealSense drivers, FlyCapture, libdc1394, videoinput and ARToolKitPlus. Those are excluded at the dependency now. javacv's own classes for them are excluded by keeping the transitive closure of what the decode path reaches, which is a shorter and more durable rule than listing what to drop. 2,314 classes became 473.

- **javacpp's `tools` package had to stay.** Dropping it was the first attempt and the release gate refused to compile against the result: JavaCPP's loader reads each binding's preset class at runtime to decide which library to extract, and those preset classes implement `InfoMapper` from `tools`. Only the four Maven mojos actually reference the Maven APIs, so only they are excluded.

- **The demo grid is 900 points on purpose.** Chromatik's FREE tier holds network output back above 1,000, so at 30x30 the demo drives real fixtures rather than only the preview. A package jar's `projects/` folder is not surfaced anywhere, so the demo is a file to open and the clip beside it goes into `~/Chromatik/LaserphileVideo/` first.

## Verification done

`mvn -o clean package` is clean, and all four `dist-*` profiles build, since the shade change touches every jar:

| Jar | Size | Classes |
|---|---|---|
| `-macos` | 43.5 MB, was 46.4 | 473, was 2,314 |
| `-windows` | 28.4 MB | 473 |
| `-linux-x86_64` | 25.7 MB | 473 |
| `-linux-arm64` | 25.5 MB | 473 |

`ci/NativeLoadCheck.java` passes against the trimmed macOS jar: natives load, `lx.package` and `VideoPattern` survived shading, ten frames decode.

A throwaway harness in the scratchpad, in the plugin's own package, runs 31 checks against `projects/demo-bars.mp4`. Colour is scored against FFmpeg's own correctly-tagged conversion of the same frame, corrected and uncorrected, so the decode path's own floor cancels out of the judgement rather than being attributed to the correction.

| Check | Result |
|---|---|
| Decode-path floor, before any correction | mean 0.931 against the BT.601 reference |
| Colour error against the BT.709 reference | mean 3.277 to 1.488 |
| Saturated bars | mean 4.150 to 1.765 |
| Neutral bands | bit-identical corrected and uncorrected, 230,400 channels |
| Magenta keeps zero green (rails held) | lowest green across the bar: 0 |
| Gamma 1.0 at full level | identity across all 256 entries |
| Gamma 2.2 | mid grey 128 becomes 56 |
| Gamma endpoints | 0 and 255 unmoved |
| Level composes with gamma | 255 at half level becomes 128 |
| Returning to defaults | flat flag restored, 128 passes through |
| `Res` default | Auto |
| `Res` 128 / 256 / 384 / 512 from a 640x480 clip | 128x96, 256x192, 384x288, 512x384 |
| `Res` larger than the source | 640x480, never enlarged |
| Auto at 900 / 10,000 / 65,536 / 0 points | 128, 200, 512, 128 |
| Background BLACK / CLEAR | `0xff000000` / `0x00000000` |
| A source that will not open | no frame, source closed, nothing thrown |
| An occasional failing grab | 9 frames through, 4 grabs failed, still running |
| A source failing every grab | gives up after 10, closes |
| A non-video file | renders nothing, engine thread untouched |
| Ten open/close cycles | 0 decode threads before, 0 after |
| Pipeline reuse after those cycles | frames flowing again |
| Audio not decoded | 60 ms either way, with the track and with it dropped |

The audio check runs against a clip carrying a 320 kbps soundtrack. Decoding every frame takes the same time whether the track is left alone or dropped, so `grabImage()` demuxes audio packets without decoding them. Neither `setAudioChannels(0)` nor `setAudioStream(-1)` changes what the grabber reports, so there is no lever worth pulling and no code change was made.

The demo project's parameter keys were cross-checked against the paths `VideoPattern` registers: all 28 are present, and it sets nothing the pattern does not have.

**Not verified here, and it needs the app:** everything the exit criteria are actually about. Colour on real LEDs is a comparison no harness can make, and the `gamma` default depends on it.

Still outstanding, in the app:

1. Open `projects/demo.lxp` with the clip staged in `~/Chromatik/LaserphileVideo/` and confirm it plays with no other setup.
2. Compare colour against the source video on the LEDs, tune `gamma` and `level`, and set the `gamma` default from what that says.
3. Cycle the pattern on and off, swap files and sources repeatedly, and watch thread count and memory.
4. Confirm the `NoClassDefFoundError` noise is gone from the log at package load.
5. Clear the M3 and M4 in-app passes at the same time, since this milestone touched both of their paths.

## Verification (planned)

Run on the actual LED installation, compare colour against the source video, cycle the pattern on/off and swap files/sources repeatedly while watching for leaks (thread count, memory), and confirm the demo `.lxp` opens and plays with no setup.
