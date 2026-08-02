<div align="center">

# 🌒 chromatik-plugins

**Content packages for [Chromatik](https://chromatik.co/), the Java digital lighting workstation.**

Play video on LEDs that aren't a screen.

[![License: MIT](https://img.shields.io/badge/License-MIT-8b5cf6.svg?style=flat-square)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-f89820.svg?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Chromatik 1.2.1](https://img.shields.io/badge/Chromatik-1.2.1-00c8ff.svg?style=flat-square)](https://chromatik.co/)
[![Platform: macOS arm64](https://img.shields.io/badge/Platform-macOS%20arm64-000000.svg?style=flat-square&logo=apple&logoColor=white)](#-requirements)
[![Status: M2](https://img.shields.io/badge/Milestone-M2%20of%205-eab308.svg?style=flat-square)](#-roadmap)

</div>

---

Chromatik renders colour onto a **point cloud**, not a framebuffer. Every LED is an `LXPoint` with a real position in 3D space, which is exactly right for a geodesic dome skinned in LED panels and exactly wrong for anything that assumes a rectangular grid of pixels.

Chromatik ships an `ImagePattern` for still images. It has **no video player**. That's the gap this repo fills.

`VideoPattern` decodes a video on a background thread and projects each frame onto whatever 3D structure you've modelled, sampling a colour per LED through a full UV projection. A flat wall is just the special case where every point shares a `z`.

> [!NOTE]
> **Status: milestone 2 of 5.** Projection works end to end and is confirmed in-app. Playback currently auto-starts and loops with no transport controls, and builds target macOS arm64 only. See the [roadmap](#-roadmap).

## 📦 What's in here

| Package | Artifact | Category in Chromatik | What it does |
|---|---|---|---|
| `laserphile.chromatik.video` | `chromatik-video` | **Laserphile → Video** | Decodes a video file and projects it onto the model |

The repo is named for what it's growing into. Sibling `laserphile.chromatik.*` packages land alongside this one as they're built.

## ✨ Features

- **Model-agnostic projection.** Works on any `LXModel`: domes, sculptures, strips, matrices. Nothing assumes a grid.
- **Never blocks the engine.** All decode, colour conversion, and downscaling happen off the LX engine thread. `run()` does a lock-free read and a tight per-point loop.
- **Full projection control.** Yaw, pitch, roll, translate on three axes, scale, per-axis stretch, and scroll.
- **Four wrap modes.** `CLAMP`, `CLIP`, `TILE`, `MIRROR`, matching the vocabulary of the built-in `ImagePattern`.
- **Transparent background.** `CLEAR` lets lower LX layers show through where the image doesn't reach.
- **Bilinear sampling.** Cuts the shimmer you get when a sparse point cloud samples a small texture.
- **Zero custom UI.** A plain `LXPattern`, so Chromatik auto-generates the control panel and the whole thing runs on the **FREE licence tier**.

## 🔧 Requirements

| | Version | Notes |
|---|---|---|
| **JDK** | 21 | [Temurin](https://adoptium.net/) recommended, to match Chromatik's own runtime |
| **Maven** | 3.9+ | |
| **Chromatik** | 1.2.1 | Pinned via `lx.version` in `pom.xml` |
| **Platform** | macOS arm64 | See the warning below |

> [!WARNING]
> **The build is currently macOS arm64 only.** `pom.xml` hardcodes `<native.classifier>macosx-arm64</native.classifier>`, so the FFmpeg native bundled into the jar only loads on Apple Silicon. Building for another platform means changing that one property to a classifier Bytedeco publishes (`linux-x86_64`, `windows-x86_64`, `macosx-x86_64`). Proper per-OS build profiles are [M5](#-roadmap).

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

## 🚀 Quick start

```bash
git clone https://github.com/moonbase-labs/chromatik-plugins.git
cd chromatik-plugins

# Build the uber-jar into target/
mvn package

# Build and drop it into ~/Chromatik/Packages
mvn -Pinstall install
```

Then:

1. **Stage a video** at `~/Chromatik/LaserphileVideo/yourclip.mp4`.
2. **Launch Chromatik.** The log should show `Loading package content from: …` followed by a `Package:Laserphile Video` line.
3. **Add the pattern** to a channel: category **Laserphile**, pattern **Video**.
4. **Point it at your file.** Type `LaserphileVideo/yourclip.mp4` into the `File` box. Editing the field restarts the decode thread on the spot.

> [!TIP]
> Relative paths resolve under `~/Chromatik/`, absolute paths are used verbatim. Prefer relative: a `.lxp` project that stores an absolute path breaks the moment someone else opens it.

> [!IMPORTANT]
> Chromatik's **FREE tier disables network output**, so Art-Net, sACN, and DDP won't drive physical fixtures. The 3D preview renders fine, which is enough to develop and test patterns against. Driving real LEDs needs a paid tier or an external output server.

## 🎛️ Parameters

Chromatik generates the panel from these automatically.

| Parameter | Type | Default | Range | Description |
|---|---|---|---|---|
| `File` | String | `LaserphileVideo/steamed-hams.mp4` | | Absolute path, or relative to `~/Chromatik` |
| `Yaw` | Compound | `0` | -180 to 180 | Rotation about the vertical axis |
| `Pitch` | Compound | `0` | -180 to 180 | Rotation about the horizontal axis |
| `Roll` | Compound | `0` | -180 to 180 | Rotation about the view axis |
| `TransX` `TransY` `TransZ` | Compound | `0` | -1 to 1 | Shift the image on each axis |
| `Scale` | Compound | `1` | 0.1 to 10 | Zoom, larger values zoom in |
| `StretchX` `StretchY` | Compound | `1` | 0.1 to 10 | Per-axis stretch on top of `Scale` |
| `ScrollX` `ScrollY` | Compound | `0` | -1 to 1 | UV offset, animate for a pan |
| `Wrap` | Enum | `CLAMP` | `CLAMP` `CLIP` `TILE` `MIRROR` | Sampling behaviour outside the image |
| `Background` | Enum | `BLACK` | `BLACK` `CLEAR` | Colour for points rejected by `CLIP` |
| `Interp` | Enum | `BILINEAR` | `NEAREST` `BILINEAR` | `NEAREST` is blocky, `BILINEAR` is smoother |

Every `Compound` parameter is modulatable, so any of them can be driven by an LFO, an envelope, or MIDI.

## 🧠 How it works

Two threads, one hand-off, no locks on the hot path.

```mermaid
flowchart LR
    subgraph decode["🎞️ Decode thread (blocking is fine)"]
        direction TB
        SRC["FileVideoSource<br/><i>FFmpeg via JavaCV</i>"]
        FRM["VideoFrame<br/><i>downscaled RGB</i>"]
        SRC --> FRM
    end

    HOLD[("FramePipeline<br/>AtomicReference")]

    subgraph engine["⚡ LX engine thread (must never block)"]
        direction TB
        RUN["VideoPattern.run(deltaMs)"]
        PRJ["Projector<br/><i>UV projection + sampling</i>"]
        COL["colors[point.index]"]
        RUN --> PRJ --> COL
    end

    FRM -- publish --> HOLD
    HOLD -. "non-blocking read" .-> RUN
    COL --> OUT(["LX output<br/>OPC / Art-Net / sACN"])

    style decode fill:#1e1b4b,stroke:#6366f1,color:#e0e7ff
    style engine fill:#422006,stroke:#eab308,color:#fef3c7
    style HOLD fill:#064e3b,stroke:#10b981,color:#d1fae5
```

The decode thread owns the FFmpeg grabber and publishes finished frames into a single-slot `AtomicReference`. The engine thread reads whatever is there and projects it. If decode falls behind, the engine re-projects the frame it already has rather than stalling the render.

Frames are downscaled on the decode thread before they're published. LED counts are in the hundreds or thousands, so sampling a few thousand points out of a 4K frame is wasted work: a small hot buffer is both faster and kinder to the cache.

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

| File | Thread | Role |
|---|---|---|
| `VideoPattern.java` | engine | Orchestrator. Owns the parameters, drives the pipeline and projector. The only public, auto-discovered class. |
| `FrameSource.java` | decode | Interface. The seam that lets screen capture drop in later without touching projection. |
| `FileVideoSource.java` | decode | Wraps `FFmpegFrameGrabber`. Video track only, so the audio is never decoded. |
| `FramePipeline.java` | both | Owns the decode thread and the frame hand-off. Idempotent start/stop with a bounded join. |
| `VideoFrame.java` | both | An immutable decoded frame. |
| `Projector.java` | engine | The per-point UV projection and sampling loop. |
| `ProjectionParams.java` | engine | Per-frame snapshot of the controls, with the rotation matrix precomputed. |

</details>

## 🗺️ Roadmap

- [x] **M0** Decode spike. Benchmarked JavaCV/FFmpeg on real footage: ~4,450 fps at 384×216, against the ~60 needed.
- [x] **M1** Skeleton. Package loads in-app, decode thread runs, engine stays non-blocking.
- [x] **M2** Projection MVP. Full UV projection, all four wrap modes, both background modes, nearest and bilinear.
- [ ] **M3** Transport. Playback clock, play/pause, loop, speed, seek and scrub, ring buffer with back-pressure and a real drop policy.
- [ ] **M4** Screen capture. A live `ScreenCaptureSource` behind the existing `FrameSource` seam.
- [ ] **M5** Polish. BT.709 colour-space correction, gamma and level, frame pooling, per-OS build profiles, a slimmer uber-jar.

Design decisions, open questions, and per-milestone detail live in [`docs/`](docs/): [`PLAN.md`](docs/PLAN.md) is the source of truth, [`PROGRESS.md`](docs/PROGRESS.md) tracks state and carries the decisions log.

## 🛠️ Development

```bash
mvn package                # build the uber-jar
mvn -Pinstall install      # build, then copy into ~/Chromatik/Packages
```

Chromatik has to be **restarted** to pick up a rebuilt package. There's no hot reload for content packages.

A few things worth knowing before you touch the code:

> [!CAUTION]
> Any enum used with an `EnumParameter` **must be `public`, and so must its enclosing class.** LX reflects on the enum's `values()` from another package. A package-private enum compiles cleanly and then throws `IllegalAccessException` the moment the pattern is instantiated. That's why `ProjectionParams` and its nested enums are public.

- **`lx.version` is pinned deliberately.** LX and GLX are `provided` scope: Chromatik supplies them at runtime through its `LXClassLoader`. If the pinned version drifts from the installed app, the API mismatch shows up at runtime, not at compile time.
- **Never relocate the `org.bytedeco` packages in the shade config.** JavaCPP looks its native libraries up as classpath resources by literal path, so relocating those packages breaks native loading in a way that's genuinely unpleasant to debug.
- **The first `FFmpegFrameGrabber.start()` per JVM costs about 6 seconds** while JavaCPP extracts natives to `~/.javacpp/cache`. Every subsequent start is ~2 ms. It happens on the decode thread, so the UI stays responsive.
- **`swscale` logs `no accelerated yuv420p->bgr24`** on startup. Benign, but it's the flag for the BT.709 colour-space work parked in M5.

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
