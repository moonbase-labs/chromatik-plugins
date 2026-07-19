# M5. Polish

**Goal:** Turn the working plugin into a shippable v1: correct colour on real LEDs, robust under bad input, sensible defaults, and a clean distribution story.

**Prerequisite:** M4 done (or M3 if screen capture is deferred).

## Tasks

- [ ] Colour-space and gamma correction: configure swscale (JavaCV) for full-range RGB so video does not render washed-out/dark; tune the `gamma` (1..3) and `level` params against a real LED test.
- [ ] `workingResolution` parameter: 128/256/384/512/AUTO; AUTO derives from point count (roughly `clamp(2*sqrt(numPoints), 128, native)`).
- [ ] Frame buffer pooling: reuse the ring's `int[]` slots to avoid per-frame GC churn.
- [ ] Error handling: bad/missing file renders the background mode plus a status; never throws on the engine thread; decode errors are logged and recoverable.
- [ ] Lifecycle hardening: confirm no thread/native-handle leaks across activate/deactivate, file change, and source change.
- [ ] Audio: confirm the decoder does not decode the audio track (save CPU).
- [ ] Demo `.lxp` with preloaded geometry and a `VideoPattern` for one-click verification.
- [ ] Per-OS/arch build profiles (if JavaCV): produce `chromatik-video-<os>-<arch>.jar`; ship macOS arm64 first.
- [ ] Distribution README: install steps, which jar to grab, licence notes (FFmpeg preset GPL/LGPL check), and known limitations.

## Exit criteria

- Video looks correct on real LEDs (not washed out); brightness/gamma tunable.
- No freeze or leak under bad input, activate/deactivate cycling, or source switching.
- A one-click demo project exists.
- Per-platform jar(s) build and install; README covers distribution and licence.

## Files touched

- `Projector.java` (gamma/level/colourspace), `FileVideoSource.java`/`FramePipeline.java` (swscale, pooling, error handling)
- `VideoPattern.java` (`workingResolution`), `pom.xml` (build profiles)
- `projects/demo.lxp`, `README.md`

## Verification

Run on the actual LED installation, compare colour against the source video, cycle the pattern on/off and swap files/sources repeatedly while watching for leaks (thread count, memory), and confirm the demo `.lxp` opens and plays with no setup.
