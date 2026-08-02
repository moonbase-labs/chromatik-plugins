# M4. Screen capture

**Goal:** Add a live screen-capture source that reuses the whole projection pipeline. This is the payoff for the `FrameSource` abstraction: a new source type, no changes to `Projector`.

**Prerequisite:** M3 done (transport + projection working). Decode-library choice from M0 determines the capture backend.

## Tasks

- [x] Add `SourceType` enum (FILE, SCREEN) and a `source` parameter on `VideoPattern`.
- [x] Implement `ScreenCaptureSource`: `isLive()==true`, `durationMs=-1`, not seekable. Backend is the JavaCV device grabber (`avfoundation` on macOS / `gdigrab` Win / `x11grab` Linux).
- [x] Live buffering path: single-slot latest-frame holder (`AtomicReference`); capture thread overwrites, engine reads latest. No timeline.
- [x] Disable/no-op transport (play/loop/speed/position/restart) when `source==SCREEN`; document that they are inert in live mode (cannot hide them without custom UI).
- [x] Handle source switching at runtime (FILE <-> SCREEN) through the existing OPEN command on the decode thread.
- [x] Parameters to pick the display and the target capture fps.

## Exit criteria

- Setting `source=SCREEN` shows the live desktop projected onto the model, updating in real time.
- Switching back to FILE resumes file playback cleanly.

## Files touched

The three parameters this adds are all kept off the control surface, alongside `Browse` and `Reload`: each one tears down the current source and opens another, and a screen device can take seconds to open, so a swept knob would thrash it. The eight knobs are unchanged.

All under `packages/chromatik-video/src/main/java/<pkg>/`.

- `ScreenCaptureSource.java`, `SourceType.java` (new)
- `VideoPattern.java` (source params, transport gating), `FramePipeline.java` (live latest-frame path, source switching, thread epoch), `FrameSource.java` (`isLive()`), `FileVideoSource.java` + `VideoFrame.java` (shared frame conversion)

## Verification

Switch to SCREEN in the app, move a window / play something on screen, and confirm it tracks live on the LEDs. Confirm CPU stays reasonable and the frame-rate meter holds.

## How it landed (deltas from the plan above)

- **The backend is FFmpeg, and `java.awt.Robot` was ruled out rather than merely not preferred.** Chromatik launches with `-XstartOnFirstThread` (`/Applications/Chromatik.app/Contents/app/Chromatik.cfg`) so GLFW can own the main thread. `Robot` forces up the macOS AWT toolkit, which wants that same thread for AppKit's run loop, and the two do not share it. FFmpeg does the capture outside any toolkit, and its frames arrive in the same shape as a decoded file's, so `Projector` needed no changes at all.
- **The screen is named, not numbered.** avfoundation lists displays among the cameras (`[0] MacBook Pro Camera`, `[1] Capture screen 0`), so a device index shifts when a webcam is plugged in. The name `Capture screen N` is stable, and FFmpeg matches on it.
- **Capture resolution is capped to a 480px longest side, after the device opens.** A Retina desktop is around 3000x2000, which is 24 MB of pixels per frame; at 30 fps that is most of a core spent on colour conversion and enough allocation to make the GC audible. The cap has to be applied *after* `start()` for two reasons: set beforehand it reaches the device as a requested capture mode (which a screen cannot satisfy), and beforehand the display's shape is still unknown, so preserving aspect means measuring first. Verified 1920x1200 -> 480x300 and 800x600 -> 480x360, aspect unchanged. The user-facing `workingResolution` control stays M5's.
- **The transport is gated by returning early, not by disabling parameters.** `serviceTransport` bails before touching the clock when the source is live, so play/loop/speed/position/restart move nothing. `position` needed no special case: it already declines to update itself when the duration is unknown.
- **A live source gets one slot, not a ring.** Back-pressure is the wrong goal for live footage: a ring would spend its depth as latency between the desktop and the LEDs. The capture thread overwrites a single `AtomicReference` and the engine reads it, so neither waits on the other and a slow engine simply misses the frames in between. Measured: rendering at a fifth of the capture rate, the engine was never more than one frame behind what had been captured.

## The permission problem, and why it shaped the design

Screen capture is gated on every desktop platform, and a refusal does not surface as an error. With no grant, FFmpeg opens the device and then **waits forever** for a first frame the OS will never send. Two things were established about that wait:

- **It cannot be bounded.** `FFmpegFrameGrabber.setTimeout` does not reach it: the block is inside the device's own header read, not in the I/O layer the grabber's interrupt callback covers. Confirmed by measurement, a 5 s timeout did not fire.
- **It does not answer an interrupt**, so `stop()` cannot join the thread cleanly.

So the design absorbs it rather than preventing it:

- `open()` runs on the capture thread, which is a daemon, so a wedged capture shows black and leaves the rest of the app alone.
- `stop()` joins with a 2 s timeout and then abandons the thread. Each decode thread captures a **start epoch** and stands down once it no longer matches, which is what stops an abandoned thread from clearing a buffer, or publishing a stale desktop frame, that a later source now owns. `running` alone could not do this: a restart sets it back to true, which an abandoned thread would read as permission to carry on.
- `FramePipeline` tracks whether *any* frame has been published, and `VideoPattern` logs once after five seconds of silence naming the permission and where to grant it. With no custom UI, the log is the only channel available to explain a black pattern.

## Verification done

`mvn -o clean package` is clean (no new warnings) and the jar is installed. A throwaway harness (scratchpad, same package so it can reach the internals) drove `FramePipeline` the way the engine thread would, with a synthetic live source standing in for the real device. All 12 checks passed:

| Check | Result |
|-------|--------|
| Live `frameFor` returns the newest captured frame | engine saw frame 24 of 24 captured |
| Live ignores the stream time it is handed | advanced 13 -> 25 at one fixed clock value |
| Live buffering adds no latency under a slow engine | never more than 1 frame behind |
| Live reports no timeline and never drains | `durationMs` -1, `isDrained` false |
| A live source producing nothing is detectable | no frame published, `frameFor` null |
| file -> screen -> file each take over cleanly | all three |
| A wedged capture is abandoned, next source unaffected | `stop()` gave up at 2002 ms, file source then decoded 640x360 |
| Screen device strings per platform | `avfoundation`/`Capture screen 1`, `gdigrab`/`desktop`, `x11grab`/`:0.1` |
| The pattern builds with the new parameters | the public-enum gotcha stays guarded |
| The first eight remote controls are all knobs | Level, Speed, Scale, ScrollX, ScrollY, Yaw, Pitch, Roll; 20 controls, the source and file controls excluded |
| M3 regression: playback tracks the clock at 1x | drift 1 ms |
| M3 regression: seek lands on its target | asked 20000 ms, landed 19986 ms |

**Not verified here, and it needs the app:** the real device open. This machine has no screen-recording grant (`screencapture` fails with "could not create image from display"), and the grant is per-application, so it belongs to Chromatik rather than to a terminal. Everything up to the open is confirmed: the device enumerates, and opening it by name gets as far as configuring the input and negotiating a pixel format.

Still outstanding, in the app:

1. Grant Chromatik screen-recording permission when it asks (**System Settings > Privacy & Security > Screen & System Audio Recording**), then restart Chromatik. Until that is done the pattern renders black and logs the reason after five seconds.
2. Set **Source** to **Screen** and confirm the desktop tracks live on the model.
3. Watch the frame-rate meter and CPU while it runs.
4. Switch back to **File** and confirm playback resumes.
5. On a multi-display machine, step **Screen** through the displays.
