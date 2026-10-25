<div align="center">

# 🌒 chromatik-plugins

**Content packages for [Chromatik](https://chromatik.co/), the Java digital lighting workstation.**

Play video, mirror your screen and run GLSL shaders on LEDs that aren't a screen.

[![License: MIT](https://img.shields.io/badge/License-MIT-8b5cf6.svg?style=flat-square)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-f89820.svg?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Chromatik 1.2.1](https://img.shields.io/badge/Chromatik-1.2.1-00c8ff.svg?style=flat-square)](https://chromatik.co/)
[![Platform: macOS · Windows · Linux](https://img.shields.io/badge/Platform-macOS%20%C2%B7%20Windows%20%C2%B7%20Linux-64748b.svg?style=flat-square)](#%EF%B8%8F-install)
[![Patterns: Video · Screen · Shader · Touch](https://img.shields.io/badge/Patterns-Video%20%C2%B7%20Screen%20%C2%B7%20Shader%20%C2%B7%20Touch-8b5cf6.svg?style=flat-square)](docs/ROADMAP.md)

[**Download the latest release**](https://github.com/moonbase-labs/chromatik-plugins/releases/latest)

</div>

---

Chromatik renders colour onto a **point cloud**, not a framebuffer: every LED has a real position in 3D space. That's exactly right for a geodesic dome skinned in LED panels, and exactly wrong for anything that assumes a rectangular grid of pixels.

These packages take an image (a video file, the live desktop, a GLSL shader) and project it onto whatever 3D structure you've modelled.

## 🎨 What's in here

| Package | In Chromatik | What it does |
|---|---|---|
| **Video** | Laserphile → Video | Plays a video file onto the model: play, loop, speed and scrub |
| **Screen Capture** | Laserphile → Screen Capture | Mirrors the live desktop onto the model |
| **Shader** | Laserphile → Shader | Runs a `.glsl` file unmodified, Processing or Shadertoy style. Every `uniform` becomes a knob, and saving the file recompiles it live |
| **Touch** | Laserphile → Touch Camera, Touch Blob OSC | Turns a webcam into a touch surface, with a calibration preview and touch blobs sent over OSC |
| **Panel Transforms** | Laserphile → Panel Transforms | Calibration helper that turns panel fixtures in place |
| **MCP** | Preferences → Plugins | Lets an AI agent such as Claude Code look at the rig and build a show |
| **Core** | (no patterns) | The shared video engine that Video, Screen Capture, Shader and Touch need |

Everything runs on Chromatik's FREE licence.

> [!NOTE]
> **Status.** Video and Screen Capture are complete. Shader is confirmed in-app on macOS; Linux is written but hasn't run on a GPU yet, and Windows isn't written. See the [roadmap](docs/ROADMAP.md).

## ⬇️ Install

No build tools needed, just [Chromatik](https://chromatik.co/).

1. **Download** from the [latest release](https://github.com/moonbase-labs/chromatik-plugins/releases/latest): the **Core** file for your computer, plus each package you want.

   | | File |
   |---|---|
   | **Core**, Mac (Apple Silicon or Intel) | `chromatik-core-<version>-macos.jar` |
   | **Core**, Windows | `chromatik-core-<version>-windows.jar` |
   | **Core**, Linux (Intel/AMD) | `chromatik-core-<version>-linux-x86_64.jar` |
   | **Core**, Linux (ARM, e.g. Raspberry Pi) | `chromatik-core-<version>-linux-arm64.jar` |
   | **Video** | `chromatik-video-<version>.jar` |
   | **Screen Capture** | `chromatik-screen-<version>.jar` |
   | **Touch** | `chromatik-touch-<version>.jar` |
   | **Panel Transforms** | `chromatik-transforms-<version>.jar` |
   | **MCP** | `chromatik-mcp-<version>.jar` |
   | **Shader**, Mac | `chromatik-shader-<version>-macos.jar` |
   | **Shader**, Windows | `chromatik-shader-<version>-windows.jar` |
   | **Shader**, Linux (Intel/AMD) | `chromatik-shader-<version>-linux-x86_64.jar` |
   | **Shader**, Linux (ARM) | `chromatik-shader-<version>-linux-arm64.jar` |

2. **Drag each file onto the Chromatik window.** Chromatik installs them for you.
3. In the **CONTENT** tab, click **Reload Package Content**.
4. **Add a pattern** to a channel: category **Laserphile**, then pick the pattern.

> [!IMPORTANT]
> **Upgrading from v0.1.0?** Delete `chromatik-video-0.1.0-*.jar` from your packages folder first. That release was a single jar carrying everything, so nothing replaces it: Chromatik would load both, giving two packages of the same name and a duplicate of every class. Saved projects are unaffected.

> [!NOTE]
> This README describes `main`, so it can mention packages or platforms that haven't been released yet. For the exact files in a release, use that release's assets and notes.

<details>
<summary><b>Prefer to place the files yourself?</b></summary>

Drop each `.jar` into your Chromatik packages folder and restart the app. Chromatik creates the folder the first time it runs.

| | |
|---|---|
| macOS | `~/Chromatik/Packages` |
| Windows | `C:\Users\<you>\Chromatik\Packages` |
| Linux | `~/Chromatik/Packages` |

</details>

<details>
<summary><b>Keeping your videos somewhere else</b></summary>

Video looks in `~/Chromatik/LaserphileVideo`, and saves anything in there as a relative path, so projects keep working on other machines. To keep clips somewhere else, such as a synced drive or a repo, move them there, delete the `LaserphileVideo` folder, and link your folder in its place:

- **Mac or Linux:** `ln -s /path/to/your/videos ~/Chromatik/LaserphileVideo`
- **Windows** (Command Prompt): `mklink /J "%USERPROFILE%\Chromatik\LaserphileVideo" "C:\path\to\your\videos"`

Browse still saves relative paths through the link. Shaders work the same way with `LaserphileShader`.

</details>

<details>
<summary><b>A pattern lists itself, then refuses to load</b></summary>

Chromatik has no way to say one package needs another, so a pattern installed without Core lists itself and then refuses to load, saying which package is missing. Install Core and restart.

</details>

<details>
<summary><b>Screen Capture renders black</b></summary>

Screen capture needs the operating system's permission, and Chromatik has to be the app that holds it. On macOS that's **System Settings → Privacy & Security → Screen & System Audio Recording**: switch Chromatik on, then restart it, since the permission is only read at launch.

Without the permission the pattern renders black rather than reporting an error. After five seconds of that, the log in `~/Chromatik/Logs` says so.

On Linux this needs an X11 session; Wayland has no X screen for FFmpeg to capture.

</details>

### 🎬 Try it without building a model

- **Video:** download [`demo.lxp`](packages/chromatik-video/projects/demo.lxp) and [`demo-bars.mp4`](packages/chromatik-video/projects/demo-bars.mp4), put the clip in `~/Chromatik/LaserphileVideo/`, then open the project. It's a 30x30 grid playing colour bars, a grey ramp and a sweeping block, so the playhead, looping and speed are easy to read.
- **Shader:** copy the six shaders in [`packages/chromatik-shader/shaders/`](packages/chromatik-shader/shaders) into `~/Chromatik/LaserphileShader/`, then open [`demo.lxp`](packages/chromatik-shader/projects/demo.lxp). That folder is also where **Browse** opens.

Both grids are 900 points, under the 1,000 that Chromatik's FREE licence will send to real fixtures.

## 🤖 Driving Chromatik from an AI agent

`chromatik-mcp` runs a [Model Context Protocol](https://modelcontextprotocol.io) server inside Chromatik. It's a plugin rather than a content package, so it has to be enabled:

1. Put `chromatik-mcp-<version>.jar` in `~/Chromatik/Packages`.
2. In **Chromatik → Preferences → Plugins**, tick **Chromatik MCP**.
3. Restart Chromatik.
4. `claude mcp add --transport http chromatik http://127.0.0.1:3579/mcp`

> [!WARNING]
> There is no authentication. The server only listens on `127.0.0.1`, but anything that can reach the port can drive the rig and edit the project.

The tools, and how writes land in Chromatik's undo history: [docs/MCP.md](docs/MCP.md).

## 🔧 Build from source

You need JDK 21 and Maven 3.9+.

```bash
git clone https://github.com/moonbase-labs/chromatik-plugins.git
cd chromatik-plugins
mvn -Pinstall install    # build everything and copy it into ~/Chromatik/Packages
```

Working on the code? Link the jars instead, so every rebuild reaches Chromatik:

```bash
mvn package                       # on Windows: mvn package -Pdist-windows
java scripts/LinkPackages.java    # links each built jar into ~/Chromatik/Packages
```

Windows needs Developer Mode for the links. Platform builds, the release gate and the gotchas are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## 📚 Docs

| | |
|---|---|
| [Parameters](docs/PARAMETERS.md) | Every control on each pattern, shader uniforms and MIDI knob order |
| [MCP](docs/MCP.md) | The agent tools and their safety limits |
| [Architecture](docs/ARCHITECTURE.md) | The packages, threading, projection maths and source layout |
| [Roadmap](docs/ROADMAP.md) | What's done and what's next |
| [Development](docs/DEVELOPMENT.md) | Building, platform jars and gotchas |
| [Adding a plugin](docs/ADDING-A-PLUGIN.md) | A new package in four files |
| [Releasing](docs/RELEASING.md) | Tagging and versioning |

## 📄 Licence

MIT. See [LICENSE](LICENSE).

> [!IMPORTANT]
> **The built jars are not purely MIT.** Core bundles FFmpeg via [JavaCV](https://github.com/bytedeco/javacv) / [Bytedeco](https://github.com/bytedeco/javacpp-presets), and the default Bytedeco FFmpeg build is **LGPL 2.1+**. If you redistribute a built jar, you take on the LGPL's obligations too, notably keeping the FFmpeg portions LGPL and letting recipients replace them. This project deliberately avoids the full-GPL `-gpl` FFmpeg variants.

## 🙏 Credits

Built for **[TeleCortex](https://github.com/Laserphile/TeleCortex)**, a 2V icosahedron geodesic dome skinned in hand-made coreflute LED panels: roughly 5,725 individually addressable LEDs, built to be shown at [Blazing Swan](https://blazingswan.com.au/) in Western Australia.

- **[Chromatik](https://chromatik.co/)** and the open-source **[LX](https://github.com/heronarts/LX)** engine, by Mark Slee / Heron Arts.
- **[Laserphile](https://blog.laserphile.com/)** (Derwent) for TeleCortex and the LED-control lineage this builds on.
- **[Moonbase Labs](https://github.com/moonbase-labs)**, a Perth collective creating experiences with light, sound, and technology.

<div align="center">
<sub>Made in Perth 🌏 for things that glow in the desert.</sub>
</div>
