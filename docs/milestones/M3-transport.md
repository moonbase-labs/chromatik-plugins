# M3. Transport

**Goal:** Full playback control from the auto-generated panel: play/pause, loop, speed, seek/scrub, restart. This is where the `PlaybackClock` and the decode-thread control mailbox earn their keep.

**Prerequisite:** M2 done (projection working).

## Tasks

- [x] Implement `PlaybackClock` (engine thread, pure): `tick(deltaMs)` advances `mediaTimeMs += playing ? deltaMs*speed : 0`, wraps on loop, holds a pending seek target. Knows duration (-1 = live).
- [x] Add transport parameters: `play` (Boolean), `loop` (Boolean), `speed` (Compound 0.1..4.0 exp), `position` (Compound 0..1, two-way), `restart` (Trigger).
- [x] Ring-buffer timing: `frameFor(targetMs)` selects the newest frame with `ptsMs <= target`, advancing past stale frames.
- [x] Control mailbox (concurrent queue + volatiles): engine posts SEEK/PAUSE/LOOP/OPEN/STOP; decode thread services them.
- [x] Seek/scrub: flush ring, `source.seek(ms)`, refill; coalesce rapid scrub seeks to the newest; snap to nearest keyframe then decode forward.
- [x] Loop: on EOF with loop set, decode thread auto-seeks to 0 (gapless), no engine round-trip.
- [x] Speed: affects the clock (frame selection) only; decode runs at native cadence.
- [x] Two-way `position`: reflect the playhead and trigger a seek when user-edited, with echo-suppression so programmatic updates do not fire a seek.
- [x] Back-pressure + drop policy per `../PLAN.md`.
- [x] Add bilinear sampling (`interpolation` Enum) and `level` (0..1) master brightness.

## Exit criteria

- Play/pause, loop, speed, seek/scrub, and restart all work from the auto panel.
- Scrubbing feels responsive (coalesced seeks); playback stays in time at speed != 1x.

## Files touched

- `src/main/java/<pkg>/PlaybackClock.java`
- `VideoPattern.java` (transport params), `FramePipeline.java` (control mailbox, seek, loop), `FileVideoSource.java` (seek), `Projector.java` (bilinear + level)

## Verification

Drive each transport control in the app and confirm behaviour; scrub the `position` slider rapidly and confirm no feedback loop and no UI freeze. Frame-rate meter stays steady throughout.

## How it landed (deltas from the plan above)

- **The clock does not wrap; frames carry a continuous timeline.** Two tasks above pull in opposite directions: a clock that wraps at the loop point, and a decode thread that loops gaplessly with no engine round-trip. Gapless won, so the wrap had to go. Every frame is stamped with a `streamTimeMs` that keeps climbing across the loop seam (the decode thread re-anchors its offset each time it rewinds), and the clock is a plain accumulator over the same timeline. Neither side has to be told where the seam is. `mediaTimeMs` (the raw decoder pts, which does restart at 0) is kept on the frame and is what drives the `position` readout.
- **The mailbox is a coalescing atomic, not a queue.** Seeks are the only real message, and coalescing is a requirement, so `AtomicReference<SeekRequest>` (newest wins) plus volatiles for looping and end-of-stream does the whole job. Each seek carries a generation number; frames still in flight from an earlier generation are dropped by the engine rather than shown, which is what stops a scrub flashing old footage. Pause and speed need no message at all: they change the clock, and the ring's back-pressure does the rest. OPEN and STOP stay as start/stop of the decode thread.
- **Parameter listeners only raise flags.** LX can fire them off the engine thread, so every mutation of the clock and the pipeline funnels through `serviceTransport()` at the top of `run()`. This also fixes a latent M1/M2 race where editing `fileName` restarted the pipeline from the UI thread.
- **End of stream with looping off** clears `play` once the buffer drains, so the playhead parks on the last frame instead of running off into empty time. `Restart` sets `play` back on.
- Bilinear sampling was already delivered in M2; only `level` was outstanding.

## Verification done

`mvn -o clean package` is clean, and a throwaway harness (scratchpad, same package so it can reach the internals) drove `FramePipeline` + `PlaybackClock` against `steamed-hams.mp4` the way the engine thread would. All checks passed:

| Check | Result |
|-------|--------|
| 1x playback tracks the clock | drift < 100 ms |
| 2x drains the ring, media time stays honest | clock +4000 ms vs media +4004 ms |
| Pause holds a single frame | same frame object across 60 renders |
| Seek lands near its target | within one frame of the request |
| Rapid scrub (10 targets, 10 ms apart) settles on the newest | yes |
| Loop seam does not jump the timeline | biggest step 34 ms (one frame at 29.97 fps) |
| Looping off reports end of stream | drains and stops |

Still outstanding: the in-app pass (drive each control from the panel, watch the frame-rate meter while scrubbing).
