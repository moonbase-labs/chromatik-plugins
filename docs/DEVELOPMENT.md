# 🛠️ Development

## Requirements

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

## Build from source

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

Check the jars before shipping them. The core jar goes on the classpath, because it has the natives; the pattern jars are arguments, so each is opened and checked in its own right rather than whichever the classpath happened to reach first. Same gate [CI](../.github/workflows/ci.yml) runs on real hardware of every platform:

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

## Day to day

```bash
mvn package                                     # build core and every plugin
mvn -Pinstall install                           # build, then copy into ~/Chromatik/Packages

mvn -pl :chromatik-video -am package            # just one plugin, and the core it needs
mvn -Pinstall install -pl :chromatik-video -am  # build and install both
```

`-am` ("also make") is not optional for a plugin: it pulls `chromatik-core` into the same reactor, and without it Maven cannot resolve the dependency unless a matching core is already in `~/.m2`. Installing a plugin without its core into `~/Chromatik/Packages` gets you a pattern that lists itself and then refuses to load.

Chromatik reloads a rebuilt package from the **CONTENT** tab: **Reload Package Content**, or leave **Auto-Reload Packages** on.

Adding a plugin is four files and one line in the root pom: [`docs/ADDING-A-PLUGIN.md`](ADDING-A-PLUGIN.md).

## Gotchas

> [!CAUTION]
> Any enum used with an `EnumParameter` **must be `public`, and so must its enclosing class.** LX reflects on the enum's `values()` from another package. A package-private enum compiles cleanly and then throws `IllegalAccessException` the moment the pattern is instantiated. That's why `ProjectionParams` and its nested enums are public.

- **`lx.version` is pinned deliberately.** LX and GLX are `provided` scope: Chromatik supplies them at runtime through its `LXClassLoader`. If the pinned version drifts from the installed app, the API mismatch shows up at runtime, not at compile time.
- **Never relocate the `org.bytedeco` packages in the shade config.** JavaCPP looks its native libraries up as classpath resources by literal path, so relocating those packages breaks native loading in a way that's genuinely unpleasant to debug.
- **The first `FFmpegFrameGrabber.start()` per JVM costs about 6 seconds** while JavaCPP extracts natives to `~/.javacpp/cache`. Every subsequent start is ~2 ms. It happens on the decode thread, so the UI stays responsive.
- **`swscale` logs `no accelerated yuv420p->bgr24`** on startup. Benign: it means the conversion runs unvectorised, not that anything is wrong with it. It's unrelated to the colour-matrix correction, which is applied after the scaler rather than inside it.
- **The scaler can't be configured through JavaCV.** `FFmpegFrameGrabber` only ever calls `sws_getCachedContext`, `sws_scale` and `sws_freeContext`, never `sws_setColorspaceDetails`, and it keeps its scaler context private. Going around it through a libavfilter graph needs the undecoded frame, and `ImageMode.RAW` keeps only the first of `yuv420p`'s three planes, because a JavaCV `Frame` carries one stride and the format needs three.
