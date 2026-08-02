<div align="center">

# 🌒 chromatik-plugins

**Content packages for [Chromatik](https://chromatik.co/), the Java digital lighting workstation.**

Play video on LEDs that aren't a screen.

[![License: MIT](https://img.shields.io/badge/License-MIT-8b5cf6.svg?style=flat-square)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-f89820.svg?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Chromatik 1.2.1](https://img.shields.io/badge/Chromatik-1.2.1-00c8ff.svg?style=flat-square)](https://chromatik.co/)
[![Platform: macOS · Windows · Linux](https://img.shields.io/badge/Platform-macOS%20%C2%B7%20Windows%20%C2%B7%20Linux-64748b.svg?style=flat-square)](#-install)
[![Status: M4](https://img.shields.io/badge/Milestone-M4%20of%205-eab308.svg?style=flat-square)](#-roadmap)

[**Download the latest release**](https://github.com/moonbase-labs/chromatik-plugins/releases/latest)

</div>

---

Chromatik renders colour onto a **point cloud**, not a framebuffer. Every LED is an `LXPoint` with a real position in 3D space, which is exactly right for a geodesic dome skinned in LED panels and exactly wrong for anything that assumes a rectangular grid of pixels.

Chromatik ships an `ImagePattern` for still images. It has **no video player**. That's the gap this repo fills.

`VideoPattern` decodes a video on a background thread and projects each frame onto whatever 3D structure you've modelled, sampling a colour per LED through a full UV projection. A flat wall is just the special case where every point shares a `z`. A second pattern does the same for the live desktop.

> [!NOTE]
> **Status: milestone 4 of 5.** Projection works end to end and is confirmed in-app. The full transport (play/pause, loop, speed, seek and scrub) and live screen capture are both code complete and pass their headless harnesses; the in-app pass on the transport controls, and on the real capture device, is still to come. See the [roadmap](#-roadmap).

## ⬇️ Install

No build tools required. You need [Chromatik](https://chromatik.co/) and nothing else.

Two files: **Core**, which carries the video engine both patterns share, plus whichever pattern you want.

1. **Download** from the [latest release](https://github.com/moonbase-labs/chromatik-plugins/releases/latest): the Core file for your computer, and one or both patterns.

   | | File |
   |---|---|
   | **Core**, Mac (any, Apple Silicon or Intel) | `chromatik-core-<version>-macos.jar` |
   | **Core**, Windows | `chromatik-core-<version>-windows.jar` |
   | **Core**, Linux (Intel/AMD) | `chromatik-core-<version>-linux-x86_64.jar` |
   | **Core**, Linux (ARM, e.g. Raspberry Pi) | `chromatik-core-<version>-linux-arm64.jar` |
   | **Video**, plays a file | `chromatik-video-<version>.jar` |
   | **Screen Capture**, mirrors the desktop | `chromatik-screen-<version>.jar` |

2. **Drag each onto the Chromatik window.** Chromatik installs them for you.
3. In the **CONTENT** tab, click **Reload Package Content**.
4. Add a pattern to a channel: category **Laserphile**, pattern **Video** or **Screen Capture**.

The Mac Core download carries both Apple Silicon and Intel builds, so there's nothing to check first. The pattern files are identical on every platform.

Chromatik has no way to express that one package needs another, so a pattern installed without Core will list itself and then refuse to load, saying which package is missing. Install Core and restart.

> [!IMPORTANT]
> **Upgrading from v0.1.0?** Delete `chromatik-video-0.1.0-*.jar` from your packages folder first. That release was a single jar carrying everything, so it isn't replaced by any of the files above: it sits alongside them, and Chromatik loads both, giving two packages of the same name and a duplicate of every class. Saved projects are unaffected, since the pattern's name and controls are unchanged.

### Try it without building a model

[`projects/demo.lxp`](packages/chromatik-video/projects/demo.lxp) is a 30x30 grid with a Video pattern already on it, pointed at [`projects/demo-bars.mp4`](packages/chromatik-video/projects/demo-bars.mp4): colour bars over a grey ramp over a block that sweeps across the loop, so the playhead, looping and speed are all readable at a glance.

Download both, put the clip in `~/Chromatik/LaserphileVideo/`, then open the project. The grid is 900 points, which is under the 1,000 that Chromatik's FREE tier will drive, so it runs real fixtures and not just the preview.

<details>
<summary><b>Prefer to place the file yourself?</b></summary>

Drop the `.jar` in your Chromatik packages folder and restart the app. Chromatik creates the folder the first time it runs.

| | |
|---|---|
| macOS | `~/Chromatik/Packages` |
| Windows | `C:\Users\<you>\Chromatik\Packages` |
| Linux | `~/Chromatik/Packages` |

</details>

<details>
<summary><b>The Screen Capture pattern renders black</b></summary>

Screen capture needs the operating system's permission, and Chromatik has to be the application that holds it. On macOS that's **System Settings → Privacy & Security → Screen & System Audio Recording**: switch Chromatik on, then restart it, since the permission is only picked up at launch.

Without the grant the capture device opens and then waits on a first frame the OS never sends, so the pattern renders black rather than reporting an error. After five seconds of that, the log in `~/Chromatik/Logs` says so.

On Linux this needs an X11 session; a Wayland session has no X screen for FFmpeg to grab.

</details>

Every release is loaded on real hardware of each platform before it ships, so the FFmpeg natives are known to load and decode on all four. Installing and playing end to end inside Chromatik is exercised on macOS, so please [open an issue](https://github.com/moonbase-labs/chromatik-plugins/issues) if another platform misbehaves.

## 📦 What's in here

| Module | Package | Category in Chromatik | What it does |
|---|---|---|---|
| [`packages/chromatik-core`](packages/chromatik-core) | `laserphile.chromatik.core` | no patterns of its own | The projection stage, the frame pipeline, and the bundled FFmpeg decode stack |
| [`packages/chromatik-video`](packages/chromatik-video) | `laserphile.chromatik.video` | **Laserphile → Video** | Plays a video file onto the model |
| [`packages/chromatik-screen`](packages/chromatik-screen) | `laserphile.chromatik.screen` | **Laserphile → Screen Capture** | Puts the live desktop onto the model |

A Maven multi-module build, one module per Chromatik content package. Chromatik discovers packages by scanning `~/Chromatik/Packages/*.jar` for a root `lx.package` file, so one jar is exactly one package and every plugin needs its own module. The root `pom.xml` is the parent: it holds the compiler settings, the `provided` LX dependencies, the `lx.package` filtering, the shade config, and the install profile, so a new module is a ~15-line pom.

**Why a core package rather than a library each plugin bundles.** Chromatik hands every jar in its packages folder to one shared class loader, and registers every public class it finds against the package that supplied it, logging an error for any name it has already seen. That isn't limited to patterns: 328 of the 332 classes it registers are `org.bytedeco`. Two plugins each carrying FFmpeg would mean 328 errors the moment both were installed, and a second 43 MB download. So the decode stack lives in one jar and the plugins take it at `provided` scope, which is why they weigh 12 KB. The same shared class loader is what lets them find it at runtime.

The repo is named for what it's growing into. Sibling `laserphile.chromatik.*` packages land alongside these as they're built, see [`docs/ADDING-A-PLUGIN.md`](docs/ADDING-A-PLUGIN.md).

## ✨ Features

- **Model-agnostic projection.** Works on any `LXModel`: domes, sculptures, strips, matrices. Nothing assumes a grid.
- **Never blocks the engine.** All decode and colour conversion happen off the LX engine thread. `run()` does a lock-free read and a tight per-point loop.
- **Full transport.** Play/pause, loop, 0.1x to 4x speed, and a two-way position slider you can scrub. Looping is gapless and scrubbing coalesces, so a fast drag doesn't queue up a hundred seeks.
- **Live screen capture.** A second pattern, **Screen Capture**, puts the desktop onto the LEDs in real time through the same projection controls. It keeps only the newest frame, so nothing buffers latency in between, and it carries no transport, because a live desktop has no playhead to move. Needs screen-recording permission for Chromatik.
- **Full projection control.** Yaw, pitch, roll, translate on three axes, scale, per-axis stretch, and scroll.
- **Four wrap modes.** `CLAMP`, `CLIP`, `TILE`, `MIRROR`, matching the vocabulary of the built-in `ImagePattern`.
- **Transparent background.** `CLEAR` lets lower LX layers show through where the image doesn't reach.
- **Bilinear sampling.** Cuts the shimmer you get when a sparse point cloud samples a small texture.
- **Native file chooser.** A `Browse` button opens the real OS open dialog, no typing paths. It opens on the current video's folder, or on the folder you browsed to last, so picking a second clip is one click away. Files under `~/Chromatik` are stored as relative paths so a shared project still finds them.
- **Zero custom UI.** A plain `LXPattern`, so Chromatik auto-generates the control panel and the whole thing runs on the **FREE licence tier**.

## 🔧 Requirements

Only for building from source. Installing a release needs none of this.

| | Version | Notes |
|---|---|---|
| **JDK** | 21 | [Temurin](https://adoptium.net/) recommended, to match Chromatik's own runtime |
| **Maven** | 3.9+ | |
| **Chromatik** | 1.2.1 | Pinned via `lx.version` in the root `pom.xml` |

<details>
<summary><b>Setting up JDK 21 on macOS</b></summary>

```bash
brew install --cask temurin@21
brew install maven

echo 'export JAVA_HOME="$(/usr/libexec/java_home -v 21)"' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
```

Open a new shell, then confirm all three agree:

```bash
java -version    # 21.x
javac -version   # 21.x
mvn -version     # should report the Temurin 21 JAVA_HOME
```

`brew install openjdk@21` works too and skips the admin password prompt, but it's keg-only, so `JAVA_HOME` has to point at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` by hand.

</details>

## 🚀 Build from source

```bash
git clone https://github.com/moonbase-labs/chromatik-plugins.git
cd chromatik-plugins

# Build for this Mac, into packages/*/target/
mvn package

# Build and drop into ~/Chromatik/Packages
mvn -Pinstall install
```

Then:

1. **Launch Chromatik.** The log should show `Loading package content from: …` followed by a `Package:Laserphile Video` line.
2. **Add the pattern** to a channel: category **Laserphile**, pattern **Video**.
3. **Pick a video.** Click `Browse`, or type a path into the `File` box. Either way the decode thread restarts on the spot.

A plain `mvn package` builds the macOS jar, which is the one this repo is developed against. Pass a profile to build for somewhere else:

```bash
mvn package -Pdist-windows
mvn package -Pdist-linux-x86_64
mvn package -Pdist-linux-arm64
```

Only `chromatik-core` varies by platform, so a profile changes that jar and leaves the two pattern jars alone. There's no cross-compilation involved, the FFmpeg native is an ordinary Maven dependency, so any machine can build any target.

Check the jars before shipping them. The core jar goes on the classpath, because it has the natives; the pattern jars are arguments, so each is opened and checked in its own right rather than whichever the classpath happened to reach first. Same gate [CI](.github/workflows/ci.yml) runs on real hardware of every platform:

```bash
java -cp packages/chromatik-core/target/chromatik-core-*-macos.jar \
     ci/NativeLoadCheck.java \
     packages/chromatik-video/target/chromatik-video-*.jar \
     packages/chromatik-screen/target/chromatik-screen-*.jar
```

> [!TIP]
> Relative paths resolve under `~/Chromatik/`, absolute paths are used verbatim. `Browse` already stores anything under `~/Chromatik` as a relative path, which keeps a `.lxp` project working when someone else opens it.

> [!IMPORTANT]
> On Chromatik's FREE tier, Art-Net, sACN, DDP, and OPC drive real fixtures for models up to **1,000 points**, with rendering capped separately at 20,000. Develop and test against the 3D preview and you stay well inside both. Go over the output cap and Chromatik holds output back for as long as the model stays over, logging `Network output is disabled due to license restrictions.` Rigs above 1,000 points want a paid tier or an external output server.

## 🎛️ Parameters

Chromatik generates each panel from these automatically. They are listed in panel order, which is the order the panel reads left to right, top to bottom. The two patterns share the projection controls and differ in everything else.

### Video

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

### Screen Capture

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

### Knob order on a control surface

A MIDI surface binds its eight device knobs to the first eight of the pattern's remote controls, and an APC40 has no way to page past the eighth. So those eight are all continuous, and they are the first eight controls in the panel, in the same order:

| Knob | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| **Video** | `Level` | `Speed` | `Scale` | `ScrollX` | `ScrollY` | `Yaw` | `Roll` | `Gamma` |
| **Screen Capture** | `Level` | `Scale` | `ScrollX` | `ScrollY` | `Yaw` | `Roll` | `Gamma` | `XScale` |

The two line up except at knob 2, where Screen Capture has no `Speed` to spend the slot on, so everything shifts up one and `XScale` reaches the row.

`Pitch` is the rotation left off the knobs. A projection sweeps it least of the three, and the freed slot goes to `Gamma`, which is what you reach for once the video is on real LEDs. `Pitch` is still on the panel and still mappable by hand.

The surface order carries on down the panel from there, so the *n*th control you read is the *n*th a surface sees.

Controls held back from the surface entirely sit at the end of the panel, which is what keeps everything ahead of them lined up. On Video that is `Res`, `Browse`, `Reload` and `File`. On Screen Capture it is `Screen`, `Cursor` and `Res`. `Browse`, `Reload` and `File` go to disk; the rest tear down the current source and open another, and a screen device can take seconds to open, so a swept knob would thrash it.

## 🧠 How it works

Two threads, one hand-off, no locks on the hot path.

```mermaid
flowchart LR
    subgraph decode["🎞️ Decode thread (blocking is fine)"]
        direction TB
        SRC["FileVideoSource<br/><i>FFmpeg via JavaCV</i>"]
        FRM["VideoFrame<br/><i>ARGB + stream time</i>"]
        SRC --> FRM
    end

    RING[("FramePipeline<br/>bounded ring, 8 frames")]
    BOX[["Mailbox<br/><i>seek · loop</i>"]]

    subgraph engine["⚡ LX engine thread (must never block)"]
        direction TB
        RUN["VideoPattern.run(deltaMs)"]
        CLK["PlaybackClock<br/><i>playhead</i>"]
        PRJ["Projector<br/><i>UV projection + sampling</i>"]
        COL["colors[point.index]"]
        RUN --> CLK --> PRJ --> COL
    end

    FRM -- "publish (blocks when full)" --> RING
    RING -. "frameFor(streamTime)" .-> RUN
    RUN -- "transport" --> BOX
    BOX -. "serviced between frames" .-> SRC
    COL --> OUT(["LX output<br/>OPC / Art-Net / sACN"])

    style decode fill:#1e1b4b,stroke:#6366f1,color:#e0e7ff
    style engine fill:#422006,stroke:#eab308,color:#fef3c7
    style RING fill:#064e3b,stroke:#10b981,color:#d1fae5
    style BOX fill:#3b0764,stroke:#a855f7,color:#f3e8ff
```

The decode thread owns the FFmpeg grabber and pushes finished frames into a small bounded ring. The engine picks the newest frame that's due at the current playhead and leaves the rest. A full ring is the only brake on decoding, which makes back-pressure fall out for free: pausing or playing below 1x stalls the decode thread on the ring, and playing above 1x drains it so decode runs flat out to keep up. If decode still can't keep up, the engine re-projects the newest frame it has and the playhead carries on, so media time stays honest and frames are dropped instead.

Control flows the other way through a mailbox the decode thread reads between frames. Seeks coalesce to the newest target and carry a generation number, so a fast scrub never flashes footage from a position you've already dragged past. Looping happens entirely on the decode thread, so the seam is gapless: it stamps every frame with a timeline that keeps climbing straight through the loop point, which is the same timeline the playhead runs on.

Frames are decoded no larger than `Res` says, which on `Auto` comes from the model's point count. Sampling a few thousand points out of a 4K frame is wasted work, and the scaler is already converting every frame so resizing rides along in the same pass. What comes out is small enough to stay in cache while the projection walks it.

Colour is put right on the way out. Video stores brightness and two colour-difference channels, and turning those into red, green and blue needs one set of coefficients for standard-definition footage and another for high-definition. The file says which; JavaCV never passes that answer to the scaler, so every file is decoded as if it were standard definition. That costs up to 32 of 255 on a strongly coloured pixel and nothing at all on a grey one, which reads as a hue and saturation shift rather than as anything obviously broken. The scaler's error is a fixed linear mix of the RGB it produced, so it is undone with another one, per decoded pixel on the decode thread. A channel that arrives sitting on 0 or 255 is left alone: the scaler clamped it before we saw it, and correcting anyway lifts green off a pure magenta bar and turns it dirty.

<details>
<summary><b>The projection maths</b></summary>

Per point, in normalised model coordinates so nothing depends on the model's real-world size:

```
c = (xn-0.5, yn-0.5, zn-0.5) - (translateX, translateY, translateZ)
r = transpose(R) * c            // inverse-rotate into the texture plane
u = r.x * invScaleX + 0.5 + scrollX
v = r.y * invScaleY + 0.5 + scrollY
                                // r.z is dropped: the projection is orthographic
```

`R = Rz(roll) * Ry(yaw) * Rx(pitch)`, built once per frame in `ProjectionParams.recompute()` along with the reciprocal scales, so the per-point loop does no trig and no division.

`(u, v)` then goes through the wrap mode, and the result is sampled nearest or bilinear. Points that `CLIP` rejects take the background colour.

This is re-implemented from the documented behaviour of Chromatik's `ImagePattern`, not copied from it. Chromatik is proprietary; the algorithms aren't.

</details>

<details>
<summary><b>Source layout</b></summary>

**`packages/chromatik-core/src/main/java/laserphile/chromatik/core/`**, shared by every plugin and public only where a plugin actually reaches it:

| File | Thread | Role |
|---|---|---|
| `FrameSource.java` | decode | Interface. The seam that lets a file and a live screen share one pipeline and one projector. |
| `FramePipeline.java` | both | Owns the decode thread and the buffer: a ring for a file, one slot for a live source. Idempotent start/stop with a bounded join. |
| `VideoFrame.java` | both | A decoded frame plus its place on the timeline. |
| `ProjectionControls.java` | engine | The 16 projection controls every pattern shares, plus the snapshot-and-project call. |
| `ProjectionParams.java` | engine | Per-frame snapshot of the controls, with the rotation matrix and the tone curve precomputed. |
| `Projector.java` | engine | The per-point UV projection and sampling loop. Package-private: `ProjectionControls` is its only caller. |
| `ColorSpaceCorrection.java` | decode | Undoes the decoder's fixed BT.601 conversion on high-definition footage. |
| `WorkingResolution.java` | decode | How big to decode, and how to make a grabber produce that size. |

**`packages/chromatik-video/…/video/`** and **`packages/chromatik-screen/…/screen/`**, one pattern each:

| File | Thread | Role |
|---|---|---|
| `VideoPattern.java` | engine | Orchestrator for file playback. Owns the transport, drives the clock and pipeline. |
| `FileVideoSource.java` | decode | Wraps `FFmpegFrameGrabber`. Video track only, so the audio is never decoded or seeked. |
| `PlaybackClock.java` | engine | The playhead. Pure state: play, speed, and the pending seek target, no I/O. |
| `ScreenCapturePattern.java` | engine | Orchestrator for the live desktop. No transport, because there is no timeline. |
| `ScreenCaptureSource.java` | capture | The desktop through FFmpeg's per-OS capture device. Live, no timeline. |

</details>

## 🗺️ Roadmap

- [x] **M0** Decode spike. Benchmarked JavaCV/FFmpeg on real footage: ~4,450 fps at 384×216, against the ~60 needed.
- [x] **M1** Skeleton. Package loads in-app, decode thread runs, engine stays non-blocking.
- [x] **M2** Projection MVP. Full UV projection, all four wrap modes, both background modes, nearest and bilinear.
- [x] **M3** Transport. Playback clock, play/pause, loop, speed, seek and scrub, ring buffer with back-pressure and a real drop policy.
- [x] **M4** Screen capture. A `Screen Capture` pattern of its own, on FFmpeg's per-OS capture device, behind the existing `FrameSource` seam with single-slot live buffering.
- [x] **M5** Polish. BT.709 colour-space correction, `Gamma`, working-resolution downscale, recoverable decode errors, a one-click demo project, and an uber-jar down from 2,314 classes to 473. (Per-OS build profiles landed early, with the release pipeline. Frame pooling was dropped: the downscale already keeps frames small enough that it earned nothing.)

Design decisions, open questions, and per-milestone detail live in [`docs/`](docs/): [`PLAN.md`](docs/PLAN.md) is the source of truth, [`PROGRESS.md`](docs/PROGRESS.md) tracks state and carries the decisions log.

## 🛠️ Development

```bash
mvn package                                     # build core and every plugin
mvn -Pinstall install                           # build, then copy into ~/Chromatik/Packages

mvn -pl :chromatik-video -am package            # just one plugin, and the core it needs
mvn -Pinstall install -pl :chromatik-video -am  # build and install both
```

`-am` ("also make") is not optional for a plugin: it pulls `chromatik-core` into the same reactor, and without it Maven cannot resolve the dependency unless a matching core is already in `~/.m2`. Installing a plugin without its core into `~/Chromatik/Packages` gets you a pattern that lists itself and then refuses to load.

Chromatik reloads a rebuilt package from the **CONTENT** tab: **Reload Package Content**, or leave **Auto-Reload Packages** on.

Adding a plugin is four files and one line in the root pom: [`docs/ADDING-A-PLUGIN.md`](docs/ADDING-A-PLUGIN.md).

### Releasing

Tagging publishes. [CI](.github/workflows/ci.yml) builds all four platform jars, loads each one on real hardware of its platform, then attaches them with checksums.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Versions are [semver](https://semver.org/): `vMAJOR.MINOR.PATCH`, optionally with a `-prerelease` suffix. A tag that isn't fails the build before anything is published, and a `-prerelease` tag (`v0.2.0-rc.1`) is marked as such on GitHub so it stays out of "latest release". Build metadata (`+`) is rejected: semver ignores it for precedence and it mangles download URLs.

The tag is the only place a release version lives. The pom stays on `-SNAPSHOT` naming the release it's heading for, and CI overwrites it at build time so the jar reports a real version through `lx.package`.

What counts as breaking, for a Chromatik package, is compatibility with saved `.lxp` projects, since those store the pattern's class name and its parameter paths:

| | Means |
|---|---|
| **MAJOR** | A saved project won't reload cleanly: a parameter renamed or removed, the pattern class renamed, or `mediaDir` changed. |
| **MINOR** | New parameters, new patterns, a newly supported platform. Existing projects unaffected. |
| **PATCH** | Fixes and performance work with no change to the parameter surface. |

While MAJOR is `0` this is pre-1.0, so a MINOR bump is allowed to break things. Reaching 1.0 is the promise not to.

A few things worth knowing before you touch the code:

> [!CAUTION]
> Any enum used with an `EnumParameter` **must be `public`, and so must its enclosing class.** LX reflects on the enum's `values()` from another package. A package-private enum compiles cleanly and then throws `IllegalAccessException` the moment the pattern is instantiated. That's why `ProjectionParams` and its nested enums are public.

- **`lx.version` is pinned deliberately.** LX and GLX are `provided` scope: Chromatik supplies them at runtime through its `LXClassLoader`. If the pinned version drifts from the installed app, the API mismatch shows up at runtime, not at compile time.
- **Never relocate the `org.bytedeco` packages in the shade config.** JavaCPP looks its native libraries up as classpath resources by literal path, so relocating those packages breaks native loading in a way that's genuinely unpleasant to debug.
- **The first `FFmpegFrameGrabber.start()` per JVM costs about 6 seconds** while JavaCPP extracts natives to `~/.javacpp/cache`. Every subsequent start is ~2 ms. It happens on the decode thread, so the UI stays responsive.
- **`swscale` logs `no accelerated yuv420p->bgr24`** on startup. Benign: it means the conversion runs unvectorised, not that anything is wrong with it. It's unrelated to the colour-matrix correction, which is applied after the scaler rather than inside it.
- **The scaler can't be configured through JavaCV.** `FFmpegFrameGrabber` only ever calls `sws_getCachedContext`, `sws_scale` and `sws_freeContext`, never `sws_setColorspaceDetails`, and it keeps its scaler context private. Going around it through a libavfilter graph needs the undecoded frame, and `ImageMode.RAW` keeps only the first of `yuv420p`'s three planes, because a JavaCV `Frame` carries one stride and the format needs three.

## 📄 Licence

This project is MIT licensed. See [LICENSE](LICENSE).

> [!IMPORTANT]
> **The built jar is not purely MIT.** It bundles FFmpeg via [JavaCV](https://github.com/bytedeco/javacv) / [Bytedeco](https://github.com/bytedeco/javacpp-presets), and the default Bytedeco FFmpeg build is **LGPL 2.1+**. The MIT licence covers the source in this repository. If you redistribute a built jar, you're also redistributing LGPL binaries and take on the LGPL's obligations, notably keeping the FFmpeg portions LGPL and letting recipients replace them.
>
> The `-gpl` classifier variants of the FFmpeg artifact are full GPL and would be far more restrictive. This project deliberately uses the default LGPL build, and you should keep it that way unless you've thought hard about the consequences.

## 🙏 Credits

Built for **[TeleCortex](https://github.com/Laserphile/TeleCortex)**, a 2V icosahedron geodesic dome skinned in hand-made coreflute LED panels: roughly 5,725 individually addressable APA102/SK9822 LEDs driven by five Teensy microcontrollers, built to be shown at [Blazing Swan](https://blazingswan.com.au/) in Western Australia.

The dome has played video before, on the bespoke Python and JavaScript stacks that preceded this. `VideoPattern` generalises that trick onto a platform with a real mixer, a real pattern engine, and a real UI.

- **[Chromatik](https://chromatik.co/)** and the open-source **[LX](https://github.com/heronarts/LX)** engine, by Mark Slee / Heron Arts.
- **[Laserphile](https://blog.laserphile.com/)** (Derwent) for TeleCortex and the LED-control lineage this builds on.
- **[Moonbase Labs](https://github.com/moonbase-labs)**, a Perth collective creating experiences with light, sound, and technology.

<div align="center">
<sub>Made in Perth 🌏 for things that glow in the desert.</sub>
</div>
