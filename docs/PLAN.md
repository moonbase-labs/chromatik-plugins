# Chromatik Video Plugin: Plan

> **New session? Read this first, then `PROGRESS.md`, then the current milestone file under `milestones/`.** Update `PROGRESS.md` as work lands.

A Chromatik content package whose headline feature is a `VideoPattern` that decodes a video (and, later, a live screen capture) and projects it onto any LED structure. This doc is the source of truth for design and decisions; `PROGRESS.md` tracks status; `milestones/M*.md` hold the executable per-milestone detail.

## Background: why this project exists

This plugin is one increment of a larger art project, not a standalone tool. The context below was reconstructed from primary sources (the Laserphile blog and GitHub org, the Moonbase Labs GitHub org, and Heron Arts docs) and adversarially fact-checked; the load-bearing claims verified 3-0. Where something is inference rather than confirmed fact, it says so.

**The end target is TeleCortex.** TeleCortex is a large custom-built LED sculpture: a 2V icosahedron geodesic dome skinned with hand-made coreflute LED panels, roughly **5,725 individually addressable APA102/SK9822 LEDs**, driven by five Teensy microcontrollers (four panels each). The lit surface is a **dome, not a flat wall**, which is the whole reason the pattern must do general-purpose 3D projection onto a sparse point cloud rather than blit a 2D image. It is built to be assembled and shown at **Blazing Swan**, a regional Burning Man event in Western Australia. Sources: `blog.laserphile.com/2018/01/software-and-electronics-for-driving.html` ("Software and electronics for driving 5725 LEDs"), `blog.laserphile.com/2018/07` (coreflute panels, 2V icosahedron), `github.com/Laserphile/TeleCortex`.

**The people.** "Laserphile" is Derwent (blog author "derwentx"), a Perth-based developer. "Moonbase Labs" (`github.com/moonbase-labs`) is a Perth art-and-technology collective ("an Australian collective creating unique experiences with light, sound, and technology") and is the org that owns this plugin's namespace (`laserphile.chromatik.video`) and the Chromatik fixtures repo. A GitHub listing shows the moonbase-labs org run by `@vanbujm` and `@achalkley` (`@vanbujm` is this repo's owner, Jonathan van Buren). The exact division of labour between Derwent and the collective is not formally documented.

**This continues an existing capability; it is not new ground.** There is a long, repeatedly-rewritten LED-control lineage in the Laserphile org:
- **TeleCortex / Python-TeleCortex**: original Python + OpenCV/Numpy stack, FastLED firmware on Teensy, pixels over USB via a G-code-like serial protocol.
- **JS-TeleCortex**: JavaScript rewrite, now archived.
- **JS-Telecortex-2** (Server / Client / Util): current and still active (a repo updated Jan 2026). NodeJS driving APA102/SK9822 strips over **Open Pixel Control (OPC)**, UDP, RGB byte order, port 42069. Benchmarked at ~200 FPS for 1,200 SK9822 pixels from a Raspberry Pi 3 over WiFi.

Crucially, the dome has **already played video**: the JS-Telecortex-2-Server README links a clip titled "Steamed Hams on a previous version of TeleCortex". That is why `test-media/steamed-hams.mp4` is our decode fixture. The VideoPattern generalises a thing the rig could already do into the Chromatik platform.

**Why Chromatik/LX.** Chromatik (formerly LX Studio; Heron Arts / Mark Slee, Java) is purpose-built for non-uniform 3D pixel layouts, "like a sparse vertex shader ... taking into account the discrete spatial position of each pixel" (`heronarts/LX` README), with an open plugin plus `LXPattern` extension surface and a track record on large installations (Burning Man's Tree of Ténéré, Titanic's End). Adopting it swaps the bespoke Python/JS render stack for a mature digital lighting workstation with mixing, transport, and a pattern engine, and TeleCortex's dome is exactly the sparse-point-cloud case LX is designed for.

**Where the VideoPattern fits the pipeline.** Moonbase Labs authors the Chromatik `.lxf` fixtures that model the dome geometry, e.g. `Chromatik-Fixtures/DomeLargeTriangle.lxf`: a 316-LED triangular dome section, 24 serpentine strips, 62.5mm spacing, output over OPC/UDP port 42069, RGB. So end to end: VideoPattern decodes a video (JavaCV/FFmpeg) -> projects a colour per LED across the dome's 3D points -> Chromatik outputs OPC -> LED strips.

**Open questions this background does not settle** (each affects the build):
- **Runtime output path**: does our Chromatik plugin emit OPC directly to the strips, or hand frames to the existing JS-Telecortex-2 OPC server on the Pi? (See the FREE-tier "network output disabled" limit in `PROGRESS.md`.)
- Is the full ~5,725-LED dome modelled in Chromatik as many `DomeLargeTriangle`-style fixtures, and what is the current pixel count versus the 2018 build?
- **Mapping strategy**: true UV projection onto the 3D point cloud versus flat 2D sampling, and the target frame rate (the M2 projection question).

## Environment setup (prerequisite, do first)

This Mac (Apple Silicon, arm64) has Homebrew at `/opt/homebrew` but **no working JDK and no Maven**. Verified state (2026-07-19): `/usr/bin/java` and `/usr/bin/javac` are macOS stubs that report "Unable to locate a Java Runtime", `/usr/libexec/java_home -V` lists no JDKs, `JAVA_HOME` is unset, and `mvn` is not on the PATH. Chromatik and its packages build and run on **Java 21** (Chromatik ships on Adoptium Temurin 21), so before any build:

1. **Install JDK 21** (Temurin, to match Chromatik's runtime):
   ```
   brew install --cask temurin@21
   ```
   The cask installs system-wide under `/Library/Java/JavaVirtualMachines` and registers with `/usr/libexec/java_home`; it may prompt for the account password. Alternative with no admin prompt (keg-only formula): `brew install openjdk@21`, whose home is `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
2. **Install Maven:**
   ```
   brew install maven
   ```
   The `maven` formula pulls in an `openjdk` runtime dependency; that is harmless because the build's JDK is fixed by `JAVA_HOME` in the next step.
3. **Point `JAVA_HOME` at 21 and persist it** (this shell is zsh):
   ```
   echo 'export JAVA_HOME="$(/usr/libexec/java_home -v 21)"' >> ~/.zshrc
   echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
   ```
   Then open a new shell (or `source ~/.zshrc`).
4. **Verify:** `java -version` and `javac -version` both report 21, and `mvn -version` runs and shows it is using the Temurin 21 `JAVA_HOME`.
5. **Install the Chromatik desktop app** (https://chromatik.co/) for the verification steps. The app bundles its own JRE, so running it does not need the system JDK, but you need it installed so `mvn install` can drop the package into `~/Chromatik/Packages` and you can see the pattern in the UI. Note the exact Chromatik build number, since `lx.version` in the pom must be pinned to it.

Record the installed Temurin and Chromatik versions in `PROGRESS.md`. This whole section is a prerequisite for M0 onward: the spike harness and every `mvn package`/`mvn install` need JDK 21 plus Maven present.

## Context

Chromatik (https://chromatik.co/) is a Java (JVM, Java 21) digital lighting workstation by Heron Arts, built on the open-source `LX` core library plus the `GLX` UI harness. It renders colour onto a **point cloud**: an `LXModel` exposes `model.points` (each an `LXPoint` with `x/y/z`, normalised `xn/yn/zn`, and an `index`), and a pattern fills an `int[] colors` buffer (packed ARGB) indexed by `point.index`. Think of it as a sparse vertex shader for LEDs rather than a screen.

There is **no native video player** in Chromatik. This is the gap we are filling. This is the first increment of a larger project, so the design prioritises a clean, extensible seam (a pluggable frame source and a source-agnostic projection stage) over feature breadth.

The nearest existing thing to reuse is the built-in `heronarts.lx.pattern.image.ImagePattern`, which "projects a 2D image file into 3D space" with a full projection/UV control set. It is effectively the still-image version of what we want. Chromatik is proprietary-licensed, so we study and **re-implement** its projection maths (algorithms are not copyrightable), we do not copy its source.

## Confirmed decisions

- **Mapping**: general-purpose projection. Reuse an `ImagePattern`-style UV projection so it works on any model; a flat 2D wall is just the special case where `zn` is constant.
- **v1 scope**: MVP **plus transport controls** (play/pause, loop, speed, seek/scrub). Live **screen capture** is a desired near-term source; design the abstraction for it now, implement it in a later milestone (M4).
- **Decode library**: undecided. M0 is a **spike** comparing JavaCV/FFmpeg vs JCodec on the user's real footage and hardware, then commits.
- **UI**: auto-generated parameter panel only. Ship a plain `LXPattern` and let Chromatik build the control panel from its `LXParameter`s. This avoids the `LXStudio.Plugin` path, which `canRunPlugins()` gates behind a Pro License. Note that a **custom device UI is not itself Pro-gated**: a pattern implementing `UIDeviceControls` is picked up directly, ahead of the plugin registry. We stay on the auto panel because it is less code, not because the alternative is barred.

## Architecture overview

Five cooperating units, split by thread ownership (engine thread vs decode thread) and by concern (source / buffering / clock / projection). Only `VideoPattern` needs to be `public` and non-abstract; Chromatik auto-discovers and registers it, no `LXPlugin` required. The rest are package-internal.

**`VideoPattern extends heronarts.lx.pattern.LXPattern`** (engine thread): the orchestrator. Owns all `LXParameter`s, the pipeline, the clock, and the projector. Its `run(double deltaMs)` ticks the clock, syncs control state to the decode thread, selects the current frame non-blockingly, snapshots the projection parameters, and projects into `colors[]`. It starts the pipeline in `onActive()` and stops it in `onInactive()` and `dispose()`. It never touches a decoder directly.

**`FrameSource`** (interface) with **`FileVideoSource`** and **`ScreenCaptureSource`**: the pluggable source seam that makes screen capture a drop-in later. Key methods: `open()`, `info()` (duration, native dimensions, fps, seekable), `readFrame(workW, workH)` (blocking, already downscaled), `isLive()`, `isSeekable()`, `seek(ms)`, `close()`. `FileVideoSource` wraps the chosen decode library; `ScreenCaptureSource` reports `isLive()==true`, no timeline, not seekable.

**`FramePipeline`** (owns the decode thread; bridges threads): spins up one background decode thread per active pattern, owns the frame buffer, and carries a lock-free control mailbox. Translates clock state into decode commands (open/seek/pause/loop/stop) and publishes decoded frames. `frameFor(mediaTimeMs)` is the non-blocking selector the engine calls.

**`PlaybackClock`** (engine thread, pure state): converts accumulated `deltaMs * speed` into a media time, handles pause, loop-wrap, and the pending seek target. No I/O.

**`Projector` + `ProjectionParams` + `VideoFrame`** (engine thread, pure): the reusable, source-agnostic UV-projection and sampling stage. Given a texture, a per-frame parameter snapshot, and the model, it fills `colors[]`.

### Threading and buffering (the critical part)

`run(deltaMs)` runs on the LX engine thread and must **never** block on I/O or decode. All decoding, colour conversion, and downscaling happen on the background decode thread. Downscaling happens there too, so the engine only ever samples a small hot buffer.

- **File playback**: a small bounded ring of `VideoFrame`s ordered by presentation time (roughly 4 to 16 deep). Downscaled frames are tiny (for example 384x216x4 bytes is about 332 KB), so a deep ring is a few MB. The decode thread fills ahead and blocks when the ring is full, which gives natural back-pressure (pause and speed below 1x throttle decode for free). The engine picks the newest frame whose presentation time is at or before the clock target and discards older frames.
- **Live capture**: a single-slot latest-frame holder (`AtomicReference`). The capture thread overwrites; the engine reads the latest. No timeline, so clock/seek/loop are disabled.
- **Dropped-frame policy**: keep media time correct rather than slowing the clock. If decode falls behind, present the latest available frame and let the clock keep advancing (drops frames); if decode runs ahead, the full ring blocks it (back-pressure).
- **Control**: a lock-free mailbox (a concurrent queue plus a few volatiles). Seek/scrub flushes the ring, seeks the source, and refills; rapid scrub seeks are coalesced to the newest. Loop auto-seeks to 0 on EOF on the decode thread (gapless). Changing the file posts an OPEN command; `open()` never runs on the engine thread.
- **Lifecycle**: start/stop are idempotent; `stop()` posts STOP, joins with a timeout, then closes the source to release native handles. Leaked decode threads or grabbers are handle leaks, so this must be airtight.
- **Memory**: LED counts are small (hundreds to thousands), so sampling thousands of points from a 4K frame is wasteful. Cap the working resolution (longest side around 256 to 512, or auto from point count), prefer FFmpeg swscale for the downscale, and pool the frame buffers to avoid per-frame GC churn.

### Projection and sampling

Mirror `ImagePattern.Image`'s parameter surface for familiarity, re-implemented. Precompute once per frame into `ProjectionParams` (rotation matrix and its transpose, reciprocal scales, aspect factor, scroll offsets) so the per-point loop is cheap.

Per point, using normalised coordinates so it is model-agnostic:

```
c = (xn-0.5, yn-0.5, zn-0.5) - (translateX, translateY, translateZ)
r = Rtranspose * c                       // inverse transform into the texture plane
u = r.x * invScaleX * aspect + 0.5 + scrollX
v = r.y * invScaleY          + 0.5 + scrollY
// r.z dropped (orthographic projection); reserved for future depth effects
```

- **Wrap mode**, matching `ImagePattern.ImageMode` constants: `CLAMP` (clamp to edge), `CLIP` (outside 0..1 takes the background), `TILE` (repeat), `MIRROR` (mirror-repeat), applied to `(u, v)`.
- **Background mode**: `BLACK` (`0xFF000000`) or `CLEAR` (`0x00000000`, transparent so lower LX layers show through) for points rejected by the wrap mode.
- **Sampling**: one shared helper, nearest or bilinear (bilinear reduces shimmer when there are few texels per point).
- **Write**: `colors[point.index] = applyLevelAndGamma(sampled)`.

The two reusable primitives, `mapPointToUV(...)` and `sample(...)`, are source-independent, so any future frame source reuses them unchanged.

## Parameters (auto-generated panel)

All standard `LXParameter`s so Chromatik renders the panel with no custom UI. Transport parameters no-op when the source is `SCREEN` (we cannot hide them without custom UI, so document the behaviour).

- **Source**: `source` (Enum: FILE, SCREEN), `fileName` (String path), `reload` (Trigger).
- **Transport** (file only): `play` (Boolean), `loop` (Boolean), `speed` (Compound, roughly 0.1 to 4.0, exponential), `position` (Compound 0 to 1, two-way playhead that also seeks when edited), `restart` (Trigger).
- **Projection** (mirror `ImagePattern.Image`'s exact field names): `yaw`/`pitch`/`roll`, `translateX/Y/Z`, `scale` (+ `scaleRange`, `scaleX`/`scaleY`), `stretchX`/`stretchY`/`stretchAspect`, `scrollX`/`scrollY`, `imageMode` (Enum: `CLAMP`/`CLIP`/`TILE`/`MIRROR`), `backgroundMode` (Enum: `BLACK`/`CLEAR`).
- **Sampling / colour**: `interpolation` (Enum NEAREST/BILINEAR), `level` (0 to 1), `gamma` (1 to 3).
- **Advanced**: `workingResolution` (Discrete: 128/256/384/512/AUTO).

**Resolved (research, superseded):** LX has no dedicated file/path parameter type, and a `StringParameter` renders in the auto panel as a plain **text box with no browse button** (GLX's `UIFileNameBox` is text-only). That part still holds. The conclusion drawn from it, that a native browse dialog needs a custom device UI and therefore a Pro License, was **wrong**, and is corrected below.

**Resolved (2026-08-02, verified against the 1.2.1 jars):** the native file chooser is reachable from a plain `LXPattern` on the FREE tier, and `browse` now ships as a `TriggerParameter`. Two facts settle it:

- **`heronarts.glx.GLX extends heronarts.lx.LX`.** The `LX` a pattern is handed at construction *is* the GLX instance when running in the desktop app, so `lx instanceof GLX` gives direct access to `showOpenFileDialog(title, description, extensions, defaultPath, callback)`. No UI layer is involved and nothing needs registering. Under a headless LX the check simply fails and the trigger no-ops.
- The custom-device-UI route would also have worked, contrary to the original finding. `LXStudio$UI.instantiateDeviceControls` checks `component instanceof UIDeviceControls` **before** consulting the plugin registry, so a pattern that implements the interface becomes its own device UI without a plugin, which is what `canRunPlugins()` gates ("Your license does not support running custom plugins" lives in glxstudio). We do not need this for the file chooser, but it is the door to a custom panel later, and it is not Pro-gated.

## Packaging

Start from the `heronarts/LXPackage` Maven template (https://github.com/heronarts/LXPackage). Verified template facts:

- The template declares **three `provided` dependencies**, all at `${lx.version}` (pinned 1.2.1): `com.heronarts:lx`, `com.heronarts:glx`, `com.heronarts:glxstudio`. (`glxstudio` is a binary-only artifact, no public repo; it is the studio UI layer, only needed if we ever add custom device UI.) Keep them `provided` and do **not** bundle them: Chromatik supplies them at runtime via `LXClassLoader`, and bundling risks duplicate-class conflicts. Pin `lx.version` to the installed Chromatik, since provided scope means compile/runtime API drift fails silently.
- `maven.compiler.release=21`, compiler args `-Xlint` and `-Xpkginfo:always`. Install is `mvn -Pinstall install`: an `install` Maven profile copies `target/<artifactId>-<version>.jar` into `~/Chromatik/Packages` via `maven-resources-plugin` (the default `mvn package` does not copy anything).
- `lx.package` manifest uses Maven resource-filtering `@...@` tokens plus `name`, `author`, `mediaDir`. Keep filtering enabled.

Changes we add:

- **Decode library must be bundled** (Chromatik does not provide it). Exact coordinates (verified on Maven Central):
  - JavaCV: `org.bytedeco:javacv:1.5.11` + `org.bytedeco:javacpp:1.5.11` + `org.bytedeco:ffmpeg:7.1-1.5.11`, pulling the **single `macosx-arm64` classifier** on `ffmpeg` and `javacpp` (the arm64 ffmpeg native is ~18.6 MB). Do **not** use the `javacv-platform`/`ffmpeg-platform` aggregators (they pull every OS). The default `ffmpeg` artifact is **LGPL**; the `-gpl` classifier variants are GPL, avoid them.
  - JCodec: `org.jcodec:jcodec:0.2.5` + `org.jcodec:jcodec-javase:0.2.5` (pure Java, ~2 MB, no classifiers). Coverage is narrow: H.264 Main-profile decode, MPEG-1/2, ProRes, VP8 I-frames, containers MP4/MOV/MKV. **No HEVC, VP9, or AV1.** If the real footage is outside this set, JavaCV is forced.
- **Add `maven-shade-plugin`** (the template has none) to produce an uber-jar containing the decode classes and natives while the LX/GLX deps stay out. For JavaCV: keep the `org/bytedeco/**/<platform>/` resource paths **verbatim** (do not relocate those packages, or JavaCPP's resource lookup breaks), and set `-Dorg.bytedeco.javacpp.cachedir.nosubdir=true` so JavaCPP extracts the dylibs correctly from a single uber-jar (it loads them as classpath resources and extracts to `~/.javacpp/cache`).
- FFmpeg natives are large, so each platform gets **its own jar via a `dist-*` Maven profile** in the module that bundles them, and the user installs the one for their machine. The natives are ordinary Maven dependencies, so any machine builds any target: no cross-compilation, and one CI runner produces the full set. Mac ships as a single jar carrying both `macosx-arm64` and `macosx-x86_64`, since making a non-developer identify their own CPU costs more than the ~17 MB it saves. See "Distribution" below.

### Repo layout

The repo is a **Maven multi-module build**, one module per Chromatik content package. Chromatik discovers packages by scanning `~/Chromatik/Packages/*.jar` for a root `lx.package` file, so one jar is exactly one package and a second plugin cannot share the first one's module.

```
pom.xml                                       parent + aggregator, packaging=pom
.github/workflows/ci.yml                      build, per-platform verify, tag-triggered release
.github/release-notes.md                      release body template
ci/NativeLoadCheck.java                       the per-platform release gate
packages/chromatik-video/
  pom.xml                                     parent, artifactId, name, deps, dist-* profiles
  src/main/resources/lx.package
  src/main/java/<pkg>/VideoPattern.java       (public, auto-discovered)
  src/main/java/<pkg>/FrameSource.java  FileVideoSource.java  ScreenCaptureSource.java
  src/main/java/<pkg>/FramePipeline.java  PlaybackClock.java
  src/main/java/<pkg>/Projector.java  ProjectionParams.java  VideoFrame.java
  projects/demo.lxp                           (optional one-click demo)
```

Everything shared lives in the root pom and is inherited: the compiler settings, the three `provided` LX dependencies, the `lx.package` resource filtering, the shade config, and the `install` profile. The decode stack sits in `dependencyManagement` only, so a future plugin that does no decoding does not inherit 27 MB of FFmpeg. Adding a plugin is covered in [`ADDING-A-PLUGIN.md`](ADDING-A-PLUGIN.md).

`mvn package` and `mvn -Pinstall install` at the repo root build and install every plugin; add `-pl :chromatik-<name>` to target one.

### Distribution

Pushing a `v*` tag publishes a GitHub Release. CI builds one jar per platform on a single Linux runner, then loads each jar on real hardware of the platform it targets before anything is published:

| Jar | Bundled natives | Size | Verified on |
|---|---|---|---|
| `-macos` | `macosx-arm64` + `macosx-x86_64` | 44 MB | `macos-15`, `macos-15-intel` |
| `-windows` | `windows-x86_64` | 30 MB | `windows-2025` |
| `-linux-x86_64` | `linux-x86_64` | 27 MB | `ubuntu-24.04` |
| `-linux-arm64` | `linux-arm64` | 27 MB | `ubuntu-24.04-arm` |

The gate is `ci/NativeLoadCheck.java`, run with Java's single-file source launcher so it needs no build step or test framework. It loads the FFmpeg natives, confirms `lx.package` and `VideoPattern.class` survived shading, and decodes ten frames from FFmpeg's synthetic `lavfi` `testsrc`, so no fixture file is involved.

Runner labels are pinned rather than floating: `macos-13` was retired in December 2025 and `macos-latest` moved to macOS 26 in July 2026, so `-latest` labels move under you. GitHub has said Intel macOS runners end in Fall 2027, which is when `macosx-x86_64` stops being verifiable on free hosted runners.

The tag supplies the version (`mvn versions:set` from `${GITHUB_REF_NAME#v}`), so the pom stays on `-SNAPSHOT` and the released jar still reports a real version in Chromatik's package list.

For installers, Chromatik takes a jar dragged onto its window (`GLX.importContentJar`) or added via **+** in **CONTENT → PACKAGES**, so the non-developer path needs no terminal and no folder navigation on any platform.

Package namespace: **`laserphile.chromatik.video`** (Laserphile brand; `laserphile.chromatik` is the umbrella for sibling packages, `.video` is this one). This is the `<pkg>` in every path above, and each module owns its own `laserphile.chromatik.*` subpackage.

**No shared code module yet.** `Projector`, `ProjectionParams`, `VideoFrame` and `FrameSource` stay package-private inside the video module. Extracting a `chromatik-core` means making them public and designing an API against a single consumer; the second plugin is what will show which of them are genuinely reusable. The parent pom makes that extraction cheap when the time comes.

## Milestones

Each is a coherent, demoable, mergeable unit. See `milestones/M*.md` for the full task checklist, exit criteria, and per-milestone verification.

- **M0. Decode spike and decision.** Throwaway harness benchmarking JavaCV/FFmpeg vs JCodec on real footage; pick the decode library; confirm the installed LX version and the file-picker parameter behaviour.
- **M1. Skeleton plus decode-to-single-point sanity.** `VideoPattern` builds, installs, appears in Chromatik; decode thread fills the buffer; `run()` paints the whole model one colour from the latest frame. Proves threading, lifecycle, non-blocking engine.
- **M2. Projection MVP.** `Projector` with full UV projection, wrap and background modes, nearest sampling; a recognisable video projects onto the model.
- **M3. Transport.** `PlaybackClock` (play/pause, loop, speed, seek/`position`, restart), ring-buffer timing, coalesced scrub, back-pressure and drop policy, bilinear sampling and `level`.
- **M4. Screen capture.** `ScreenCaptureSource`, `SourceType` enum, live latest-frame path, transport no-ops for live.
- **M5. Polish.** Colour-space and gamma correction, working-resolution auto, frame pooling, error handling, demo `.lxp`, per-OS build profiles, distribution README.

## Risks and open questions

- **Colour space** (high impact, easy to miss): video is usually limited-range BT.709 YUV; a naive grab yields washed-out or dark LEDs. Configure swscale for full-range RGB and add `gamma`/`level` params; budget tuning in M5.
- **Native binary size and macOS Gatekeeper**: JavaCV FFmpeg natives are large (per-OS jars). Bytedeco extracts dylibs to a cache at runtime; verify this works under `LXClassLoader` and that unsigned/quarantined dylibs load on a notarised Mac.
- **FFmpeg licensing**: the default Bytedeco `ffmpeg` artifact is **LGPL** (only the `-gpl` classifier variants are GPL), so using the default build keeps distribution simple. JCodec is permissive (BSD-style). Re-implementing `ImagePattern`'s maths is fine; do not copy proprietary source.
- **File-path portability**: LX resolves paths via `lx.getMediaFile(Media, path, create)`, absolute paths verbatim (not portable), relative paths under `~/Chromatik/<TypeDir>`. Store the video path **relative** to the package `mediaDir` and resolve at load time via `lx.getMediaFile(...)` so a shared `.lxp` stays portable (the media file must ship alongside). Whether the built-in `ImagePattern` itself stores absolute or relative is unverified (its source is closed), so follow this rule in our own code rather than copying its behaviour.
- **Two-way `position`**: distinguish user scrub from programmatic playhead updates (echo-suppression flag) to avoid a seek feedback loop.
- **Thread/lifecycle leaks**: the decode thread and native grabber must stop and close on `onInactive()`, `dispose()`, and source change; `stop()` idempotent with a bounded join.
- **Seek accuracy**: seeks snap to keyframes; scrubbing is coarse unless we decode forward from the keyframe (adds latency).
- **Audio**: LX has no audio output, so do not decode the audio track (disable it in the grabber) to save CPU.
- **LX version coupling**: provided scope means the app's LX runs at runtime; pin `lx.version` to the installed Chromatik to avoid silent API drift.

## Verification

- **M0**: the spike harness produces the metrics table and a working decode-to-`int[]` proof for each candidate library; the decision is recorded with the numbers behind it.
- **Per milestone (M1 onward)**: run `mvn package`, install into Chromatik (`mvn install` or drag the jar onto the app / CONTENT tab), add the `VideoPattern` to a channel over a test model, and confirm the milestone's exit behaviour visually in the app (colour flash in M1, recognisable projection in M2, working transport in M3, live desktop in M4). Include a small demo `.lxp` with preloaded geometry so each check is one click.
- **Non-blocking engine**: confirm the LX frame rate holds steady while decoding by watching the app's frame-rate meter, and verify a deliberately heavy file or a missing file never freezes the UI (renders background plus a status instead).

## Reference material

- Developer docs: https://chromatik.co/develop/ (packages, plugins, devices, coding).
- Package template pom: https://github.com/heronarts/LXPackage/blob/master/pom.xml
- `ImagePattern.Image` API (the parameter surface to mirror): https://chromatik.co/api/heronarts/lx/pattern/image/ImagePattern.Image.html
- API root: https://chromatik.co/api/ ; LX core repo: https://github.com/heronarts/LX
- JavaCV: https://github.com/bytedeco/javacv ; JCodec: https://github.com/jcodec/jcodec
