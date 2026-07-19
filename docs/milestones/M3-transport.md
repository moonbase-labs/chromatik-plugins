# M3. Transport

**Goal:** Full playback control from the auto-generated panel: play/pause, loop, speed, seek/scrub, restart. This is where the `PlaybackClock` and the decode-thread control mailbox earn their keep.

**Prerequisite:** M2 done (projection working).

## Tasks

- [ ] Implement `PlaybackClock` (engine thread, pure): `tick(deltaMs)` advances `mediaTimeMs += playing ? deltaMs*speed : 0`, wraps on loop, holds a pending seek target. Knows duration (-1 = live).
- [ ] Add transport parameters: `play` (Boolean), `loop` (Boolean), `speed` (Compound 0.1..4.0 exp), `position` (Compound 0..1, two-way), `restart` (Trigger).
- [ ] Ring-buffer timing: `frameFor(targetMs)` selects the newest frame with `ptsMs <= target`, advancing past stale frames.
- [ ] Control mailbox (concurrent queue + volatiles): engine posts SEEK/PAUSE/LOOP/OPEN/STOP; decode thread services them.
- [ ] Seek/scrub: flush ring, `source.seek(ms)`, refill; coalesce rapid scrub seeks to the newest; snap to nearest keyframe then decode forward.
- [ ] Loop: on EOF with loop set, decode thread auto-seeks to 0 (gapless), no engine round-trip.
- [ ] Speed: affects the clock (frame selection) only; decode runs at native cadence.
- [ ] Two-way `position`: reflect the playhead and trigger a seek when user-edited, with echo-suppression so programmatic updates do not fire a seek.
- [ ] Back-pressure + drop policy per `../PLAN.md`.
- [ ] Add bilinear sampling (`interpolation` Enum) and `level` (0..1) master brightness.

## Exit criteria

- Play/pause, loop, speed, seek/scrub, and restart all work from the auto panel.
- Scrubbing feels responsive (coalesced seeks); playback stays in time at speed != 1x.

## Files touched

- `src/main/java/<pkg>/PlaybackClock.java`
- `VideoPattern.java` (transport params), `FramePipeline.java` (control mailbox, seek, loop), `FileVideoSource.java` (seek), `Projector.java` (bilinear + level)

## Verification

Drive each transport control in the app and confirm behaviour; scrub the `position` slider rapidly and confirm no feedback loop and no UI freeze. Frame-rate meter stays steady throughout.
