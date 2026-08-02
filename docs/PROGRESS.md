# Progress

> **Fresh session, start here:** read `PLAN.md`, then the current milestone file under `milestones/`, then update this file as work lands. Keep entries short and factual.

## Current status

- **Current milestone:** M3 code complete and verified headlessly (play/pause, loop, speed, seek/scrub, restart, `level`); the sleep-pacing is gone, replaced by a playback clock + bounded ring with back-pressure. **Awaiting the in-app pass:** the jar is installed and confirmed loading in-app (`Package:Laserphile Video ... buildTimestamp:2026-08-02T02:40:14Z`), so drive each transport control in the panel and watch the frame-rate meter while scrubbing. Next after that: M4 screen capture.
- **Last updated:** 2026-08-02 (M3 built and headless-verified).

## Milestone tracker

| Milestone | State | Notes |
|-----------|-------|-------|
| Environment setup | done | Temurin 21.0.11 + Maven 3.9.16; JAVA_HOME wired in `~/.zshrc`; Chromatik 1.2.1 (FREE licence). |
| M0 Decode spike | done | JavaCV chosen. Native FFmpeg loads on arm64; ~4,450 fps decode @384x216 (vs ~60 needed); per-pixel RGB works. Spike harness in scratchpad. |
| M1 Skeleton | done | Native load confirmed in-app; decode thread + latest-frame pipeline + non-blocking run() working (no decode errors in log). Test video staged at `~/Chromatik/LaserphileVideo/steamed-hams.mp4`. |
| M2 Projection MVP | done | Full projection confirmed in-app: yaw/pitch/roll, translateX/Y/Z, scale, stretchX/Y, scrollX/Y, wrap (CLAMP/CLIP/TILE/MIRROR), background (BLACK/CLEAR), nearest/bilinear. Deferred to later: scaleX/scaleY + stretchAspect (redundant for now), master level/gamma. |
| M3 Transport | in progress | Code complete: `PlaybackClock`, bounded ring + back-pressure, coalesced seeks, gapless loop, two-way `position`, `level`. Headless harness passes all 8 checks. In-app pass outstanding. |
| M4 Screen capture | not started | |
| M5 Polish | not started | Includes: trim the uber-jar. JavaCV's optional integration classes (JavaFX/JOGL converters, JavaCPP BuildMojo) cause benign `NoClassDefFoundError` noise during Chromatik's package class-scan; exclude them via shade filters / dependency exclusions. |

States: not started / in progress / blocked / done.

## Decisions log

Record each decision with a date and one-line rationale.

- 2026-07-19: Mapping = general-purpose projection (reuse ImagePattern-style UV maths).
- 2026-07-19: v1 scope = MVP + transport controls; screen capture designed for now, built in M4.
- 2026-07-19: UI = auto-generated parameter panel only (plain LXPattern, no LXPlugin, avoids Pro License).
- 2026-07-19: Decode library = UNDECIDED, to be settled by the M0 spike.
- 2026-07-19: Package namespace = `laserphile.chromatik.video` (Laserphile brand; room for sibling `laserphile.chromatik.*` packages).
- 2026-07-19: File input via a `StringParameter` text box (typed/pasted path) + `reload` trigger. A native file-browse button is deferred: it needs a custom device UI (the plugin path / Pro License), which the auto-panel decision rules out for now. Revisit after M5 if the typed-path UX is annoying.
- 2026-07-19: Decode library = **JavaCV/FFmpeg** (`org.bytedeco` 1.5.11 / ffmpeg 7.1-1.5.11). Rationale: screen capture is wanted (JCodec can't), and the spike showed native load + huge decode headroom (~4,450 fps @384x216) + working per-pixel RGB. JCodec not benchmarked (screen-capture requirement already decided it).
- 2026-07-19 (gotcha): `EnumParameter` reflects on the enum's `values()` across packages, so any enum used with it (and its enclosing class if nested) MUST be `public`. A package-private enum compiles fine but throws `IllegalAccessException` at pattern instantiation. `ProjectionParams` + its enums are public for this reason.
- 2026-08-02 (M3): playback time is a **continuous stream timeline**, not a wrapping media clock. The decode thread loops gaplessly and re-anchors its offset each rewind, stamping every frame with a `streamTimeMs` that climbs straight through the loop seam; the engine clock is a plain accumulator over the same timeline. Rationale: a wrapping clock and a gapless decode-side loop cannot both be the authority on where the seam is. Raw pts stays on the frame as `mediaTimeMs` and drives the `position` readout.
- 2026-08-02 (M3): the control mailbox is an `AtomicReference<SeekRequest>` (newest wins) plus volatiles, not a queue. Seeks are the only message that needs one, and they must coalesce; pause and speed are pure clock state and throttle decode through the ring's back-pressure instead.
- 2026-08-02 (M3 gotcha): LX parameter listeners can fire on the UI thread, so they only raise volatile flags and `run()` acts on them. All clock/pipeline mutation stays on the engine thread. This also closed a latent M1/M2 race where editing `fileName` restarted the decode thread from the UI thread.
- 2026-08-02: repo is now a **Maven multi-module build**, one module per Chromatik content package. The video plugin moved to `packages/chromatik-video/` and the root `pom.xml` became a parent + aggregator. Rationale: Chromatik discovers packages by scanning for a root `lx.package` per jar, so one jar is one package and a second plugin can never share the first one's module. Everything identical across plugins (compiler args, the three `provided` LX deps, `lx.package` filtering, the shade config, the `install` profile) lives in the parent and is inherited, so a new plugin is a ~15-line pom. The decode stack sits in `dependencyManagement` only, not `dependencies`, so a plugin that does no decoding doesn't inherit 22 MB of FFmpeg. Recipe in `ADDING-A-PLUGIN.md`. `mvn package` / `mvn -Pinstall install` at the root behave exactly as before.
- 2026-08-02: **no shared `chromatik-core` module yet.** `Projector`, `ProjectionParams`, `VideoFrame` and `FrameSource` stay package-private in the video module. Extracting them forces them public and means designing an API against a single consumer; plugin #2 is what will show which are genuinely reusable. The parent pom makes the extraction cheap later.
- 2026-08-02: **Turborepo considered and rejected.** It's a JS-workspace task runner and this repo has no JavaScript (no Node installed either). Maven's reactor already does module ordering and `-pl` selection. Its one real advantage, input-hashed caching so touching one plugin doesn't re-shade every plugin's uber-jar, only pays off at roughly five plugins. Revisit then; adding it is purely additive (root `package.json` + `turbo.json` + a thin `package.json` per module, no files move). The `<relativePath>../../pom.xml</relativePath>` in each child is what would let a task runner drive modules independently. `maven-build-cache-extension` is the no-JS alternative if caching alone is the goal.
- 2026-07-19 (M0 findings to carry): (a) first `FFmpegFrameGrabber.start()` per JVM costs ~6.3 s of native extraction, then ~2 ms, do it on the decode thread / consider pre-warming; (b) swscale logs "no accelerated yuv420p->bgr24", benign, but flags the BT.709 colour-space work for M5; (c) use `grabImage()` (video only) so the audio track is never decoded.

## Environment / versions

- Temurin JDK (build): `21.0.11` (arm64) at `/Library/Java/JavaVirtualMachines/temurin-21.jdk`
- Maven: `3.9.16` (brew), runs on `JAVA_HOME`=Temurin 21
- Chromatik: `1.2.1` (from `~/Chromatik/Logs`); runs on its own bundled Java `21.0.7`. Licence tier = **FREE** (`1.0.0 FREE - heronarts.lx.core`). App is at `/Applications/Chromatik.app`, launch with `open -a Chromatik`.
- `lx.version` pinned in pom: `1.2.1` (matches installed Chromatik)

## Open questions

- **FREE-tier network output (larger-project concern, not a plugin blocker):** the log shows `Network output is disabled due to license restrictions` on FREE. Rendering/preview works fine (that is what we develop against), but driving physical LEDs over Art-Net/sACN/DDP is limited on FREE. If the larger project outputs to real hardware, check what the FREE tier actually allows or whether a paid tier is needed. Does not affect building/testing the video pattern.

## Resolved (2026-07-19 research)

- **Licensing (existential) — CONFIRMED OK**: custom content packages load and run under the FREE tier. Smoke test (`SmokeTestPattern`, solid red) built, installed to `~/Chromatik/Packages`, auto-discovered, and rendered in-app. Log format for a loaded package: `Loading package content from: <jar>` then `Package:<name> version:<v> lxVersion:<v> buildTimestamp:<ts>`. The no-plugin/auto-panel design stays valid (and the Pro-gated plugin/custom-UI path is genuinely unavailable on FREE).

- **File-picker**: LX has no dedicated file/path parameter type; a `StringParameter` renders as a text box with no browse button (GLX `UIFileNameBox` is text-only). A native browse dialog needs a custom device UI calling `GLX.showOpenFileDialog(...)`, i.e. the plugin path / Pro License. Auto panel = user types the path + `reload`. (See decisions log.)
- **FFmpeg licence**: the default Bytedeco `ffmpeg` artifact is LGPL; only the `-gpl` classifier variants are GPL. Use the default LGPL build.
- **Media-path resolution**: `lx.getMediaFile(Media, path, create)` uses absolute paths verbatim and resolves relative paths under `~/Chromatik/<TypeDir>`. Store paths relative to the package `mediaDir` for portability. (ImagePattern's own persistence is closed/unverified; follow the rule in our code.)
- **Decode coordinates**: JavaCV `1.5.11` / ffmpeg `7.1-1.5.11` (macosx-arm64 native ~18.6 MB); JCodec `0.2.5` (pure Java, no HEVC/VP9/AV1). See PLAN.md packaging.
