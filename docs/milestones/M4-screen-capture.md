# M4. Screen capture

**Goal:** Add a live screen-capture source that reuses the whole projection pipeline. This is the payoff for the `FrameSource` abstraction: a new source type, no changes to `Projector`.

**Prerequisite:** M3 done (transport + projection working). Decode-library choice from M0 determines the capture backend.

## Tasks

- [ ] Add `SourceType` enum (FILE, SCREEN) and a `source` parameter on `VideoPattern`.
- [ ] Implement `ScreenCaptureSource`: `isLive()==true`, `durationMs=-1`, not seekable. Backend is either the JavaCV device grabber (`avfoundation` on macOS / `gdigrab` Win / `x11grab` Linux) if JavaCV was chosen, or `java.awt.Robot.createScreenCapture` as the pure-JDK fallback.
- [ ] Live buffering path: single-slot latest-frame holder (`AtomicReference`); capture thread overwrites, engine reads latest. No timeline.
- [ ] Disable/no-op transport (play/loop/speed/position/restart) when `source==SCREEN`; document that they are inert in live mode (cannot hide them without custom UI).
- [ ] Handle source switching at runtime (FILE <-> SCREEN) through the existing OPEN command on the decode thread.
- [ ] (If useful) parameters to pick display/region and target capture fps.

## Exit criteria

- Setting `source=SCREEN` shows the live desktop projected onto the model, updating in real time.
- Switching back to FILE resumes file playback cleanly.

## Files touched

- `src/main/java/<pkg>/ScreenCaptureSource.java`
- `VideoPattern.java` (source param, transport gating), `FramePipeline.java` (live latest-frame path, source switching)

## Verification

Switch to SCREEN in the app, move a window / play something on screen, and confirm it tracks live on the LEDs. Confirm CPU stays reasonable and the frame-rate meter holds.
