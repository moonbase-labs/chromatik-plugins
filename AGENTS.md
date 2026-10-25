# chromatik-plugins

Notes for coding agents. The README is written for people installing and using the packages; this is for working on the code.

## Start here

- [`docs/PLAN.md`](docs/PLAN.md) is the source of truth for the design. [`docs/PROGRESS.md`](docs/PROGRESS.md) tracks state and holds the decisions log: read the log before changing anything it covers. Per-milestone detail is in [`docs/milestones/`](docs/milestones).
- `PROGRESS.md` was last updated on 2026-08-02, so it doesn't cover Touch, Panel Transforms or the v0.2.x releases. Touch has its own plan in [`docs/WEBCAM-TOUCH-PLAN.md`](docs/WEBCAM-TOUCH-PLAN.md).
- Other docs: [`DEVELOPMENT.md`](docs/DEVELOPMENT.md) (build, release gate, gotchas), [`ADDING-A-PLUGIN.md`](docs/ADDING-A-PLUGIN.md), [`RELEASING.md`](docs/RELEASING.md) (including versioning), [`ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`PARAMETERS.md`](docs/PARAMETERS.md), [`MCP.md`](docs/MCP.md), [`ROADMAP.md`](docs/ROADMAP.md).

## Layout

- A Maven multi-module build: one module per Chromatik package under `packages/`, each with Java package `laserphile.chromatik.<name>`. The root `pom.xml` is both parent and aggregator.
- `chromatik-core` is the shared projection and decode stack. Video, Screen Capture, Shader and Touch depend on it; MCP and Panel Transforms don't.
- `ci/` holds single-file Java programs that gate releases (`NativeLoadCheck`, `McpProtocolCheck`), run by `.github/workflows/ci.yml` on real hardware of each platform.

## Build and verify

```bash
mvn package                                     # core and every plugin; macOS jars by default
mvn -pl :chromatik-video -am package            # one plugin and the core it needs (-am is required)
mvn -Pinstall install                           # build, then copy into ~/Chromatik/Packages
java scripts/LinkPackages.java                  # or link the built jars into ~/Chromatik/Packages

java -cp packages/chromatik-core/target/chromatik-core-*-macos.jar \
     ci/NativeLoadCheck.java \
     packages/chromatik-video/target/chromatik-video-*.jar \
     packages/chromatik-screen/target/chromatik-screen-*.jar
```

- In-app behaviour is verified by driving a running Chromatik through the MCP plugin (`lx_look`, `lx_device`, `lx_set`). Headless harnesses have passed while the app was broken, so don't treat them as proof on their own.
- Running headless, use `--classpath-plugin` with the MCP jar uninstalled, or `--enable-plugin` against the installed jar. Never both: the plugin starts twice in one process.

## Rules that bite

- **Breaking means `.lxp` compatibility.** Saved projects store each pattern's class name and its parameter path keys, so renaming either is a MAJOR change. See [`RELEASING.md`](docs/RELEASING.md).
- **Plugins take `chromatik-core` and JavaCV at `provided` scope.** At `compile` scope the build still passes, but the jar duplicates 328 classes and Chromatik logs an error for each.
- **Enums used with `EnumParameter` must be `public`, and so must their enclosing class,** or the pattern throws `IllegalAccessException` when added.
- **Never relocate `org.bytedeco` in the shade config.** JavaCPP finds its natives by literal classpath path.
- **`lx.version` is pinned to the installed Chromatik (1.2.1).** A mismatch only shows up at runtime.
- **Knob order:** the first eight remote controls should be continuous, and `addParameter` order should match them. See the gotchas in [`ADDING-A-PLUGIN.md`](docs/ADDING-A-PLUGIN.md).
- **Threads:** parameter listeners can fire on the UI thread, so they only raise volatile flags and `run()` acts on them. Clock and pipeline state change only on the engine thread.
- **An exception escaping an engine task kills Chromatik for the session.** Catch `Throwable` in every task posted to the engine.
- **`LXCommandEngine.perform` swallows failures** and clears the undo stack. Validate before performing, and read the value back after.
- **No `LXPlugin` is needed for a content package.** A custom device panel works on the FREE licence by implementing `UIDeviceControls`.
