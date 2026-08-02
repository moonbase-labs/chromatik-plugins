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
- [x] Per-OS/arch build profiles: `dist-macos` (both architectures in one jar), `dist-windows`, `dist-linux-x86_64`, `dist-linux-arm64`. Landed early with the release pipeline.
- [x] Distribution: tag-triggered GitHub Release, per-platform verification on real runners, install steps and LGPL notice in the README and the release template.

## Exit criteria

- Video looks correct on real LEDs (not washed out); brightness/gamma tunable.
- No freeze or leak under bad input, activate/deactivate cycling, or source switching.
- A one-click demo project exists.
- Per-platform jar(s) build and install; README covers distribution and licence.

## Files touched

- `Projector.java` (gamma/level/colourspace), `FileVideoSource.java`/`FramePipeline.java` (swscale, pooling, error handling)
- `VideoPattern.java` (`workingResolution`)
- `packages/chromatik-video/pom.xml` for the uber-jar trim: the shade filters are specific to the decode stack, so they belong in the module rather than the root pom.
- `packages/chromatik-video/projects/demo.lxp`, `README.md`

## Verification

Run on the actual LED installation, compare colour against the source video, cycle the pattern on/off and swap files/sources repeatedly while watching for leaks (thread count, memory), and confirm the demo `.lxp` opens and plays with no setup.
