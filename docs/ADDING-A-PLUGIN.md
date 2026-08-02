# Adding a plugin

Chromatik discovers content packages by scanning `~/Chromatik/Packages/*.jar` for a file called `lx.package` at the jar root. One jar is exactly one package, so every plugin needs its own Maven module.

The root `pom.xml` is a parent as well as an aggregator. It already holds the compiler settings, the three `provided` LX dependencies, the `lx.package` resource filtering, the shade config, and the `install` profile, so a new module inherits all of that and stays small.

Substitute your plugin's name for `<name>` throughout. Use `chromatik-audio` as the running example.

## 1. Create the module directory

```
packages/chromatik-<name>/
└── src/main/
    ├── java/laserphile/chromatik/<name>/
    └── resources/
```

Keep the directory name, the artifactId and the Java subpackage in step: `packages/chromatik-audio` holds `laserphile:chromatik-audio` and the package `laserphile.chromatik.audio`.

## 2. Write `packages/chromatik-<name>/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>laserphile</groupId>
        <artifactId>chromatik-plugins</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>chromatik-audio</artifactId>
    <name>Laserphile Audio</name>
</project>
```

That's the whole file when the plugin needs nothing beyond LX. No `<groupId>`, `<version>`, `<packaging>`, `<build>` or `<profiles>`: all inherited.

`<relativePath>` is not optional. Maven's default is `../pom.xml`, which is wrong two levels down.

`<name>` is what Chromatik shows in its log line and package list, via the `@project.name@` token in `lx.package`.

### If the plugin needs extra dependencies

Declare them in the module's `<dependencies>`. Anything at `compile` scope gets shaded into the jar, which is what you want for a runtime library Chromatik does not supply.

If it's new and more than one plugin will ever want it, add it to the root pom's `<dependencyManagement>` first, then declare it unversioned here. Don't add it to the root `<dependencies>` unless every plugin genuinely needs it: that block is inherited, and FFmpeg alone is 27 MB per jar.

### If the plugin puts a frame on the model

Depend on `chromatik-core` at **`provided`** scope, and on anything of its it compiles against, such as `org.bytedeco:javacv`, the same way. `packages/chromatik-video/pom.xml` and `packages/chromatik-screen/pom.xml` are both worked examples, and both are about twenty lines.

Do not take those at `compile` scope. Chromatik hands every jar in its packages folder to one shared class loader, so `provided` is enough for the classes to be there at runtime, and it keeps the jar at a few tens of kilobytes. At `compile` scope shade inlines the whole decode stack instead: the plugin still builds, still passes the release gate, and still runs, but it duplicates all 328 classes that `chromatik-core` registers, and Chromatik logs an error for each one as soon as both are installed. Nothing catches that except the jar-size assertion in CI, which is why that assertion exists.

The trade is that Chromatik cannot express a dependency between packages, so a plugin can be installed without its core. Each of ours checks for it in a static block and says so; copy that.

### If the plugin bundles platform-specific natives of its own

A plugin whose dependencies are pure Java, including one that gets its natives from `chromatik-core`, needs nothing here. It builds one jar, named `chromatik-<name>-<version>.jar`, that runs everywhere.

A plugin that bundles a native library of its own needs one jar per platform, because the native inside only runs on the platform it was built for. Give it a `dist-*` profile per target, each declaring that platform's classifier and setting `<build><finalName>` so the jar says which platform it is. `packages/chromatik-core/pom.xml` is the worked example, including a Mac profile that carries both architectures in one jar so Mac users don't have to identify their own CPU.

Then add the new profiles to the build loop in `.github/workflows/ci.yml`, and a `verify` matrix entry per platform so each jar gets loaded on real hardware before it ships.

Prefer adding the native to `chromatik-core` over bundling it here, for the duplicate-class reason above: two packages that both carry the same library collide, however small it is.

## 3. Write `packages/chromatik-<name>/src/main/resources/lx.package`

```
{
  name: "Laserphile Audio",
  mediaDir: "LaserphileAudio",
  author: "Jonathan Van Buren",

  build: {
    name: "@project.name@",
    version: "@project.version@",
    groupId: "@project.groupId@",
    artifactId: "@project.artifactId@",
    lxVersion: "@lx.version@",
    buildTimestamp: "@maven.build.timestamp@"
  }
}
```

The `@...@` tokens are resolved by the inherited resource-filtering execution. Copy them verbatim.

**`mediaDir` must be unique across plugins.** It names a real directory, `~/Chromatik/<mediaDir>`, that Chromatik creates and that `lx.getMediaFile(...)` resolves relative paths against. Two plugins sharing a `mediaDir` share a media folder.

Leave it out if the plugin has no media of its own. `LXClassLoader` never reads it, only `LXRegistry` does, and an unused entry is a folder in everyone's home directory for nothing. `chromatik-core` and `chromatik-screen` both omit it; `chromatik-video` keeps `LaserphileVideo` because that is where clips are staged.

## 4. Register the module

Add one line to the root `pom.xml`:

```xml
<modules>
    <module>packages/chromatik-core</module>
    <module>packages/chromatik-video</module>
    <module>packages/chromatik-screen</module>
    <module>packages/chromatik-audio</module>
</modules>
```

`chromatik-core` stays first, since every other module compiles against it.

## 5. Build and install

```bash
mvn package                                       # core and every plugin
mvn -pl :chromatik-audio -am package              # just this one, and the core it needs
mvn -Pinstall install -pl :chromatik-audio -am    # build and copy both into ~/Chromatik/Packages
```

`-am` ("also make") is required for anything depending on `chromatik-core`: without it Maven leaves core out of the reactor and fails to resolve the dependency unless a matching version is already in `~/.m2`. Installing the plugin on its own also leaves Chromatik with a pattern it cannot load.

Chromatik has to be **restarted** to pick up a new or rebuilt package. There is no hot reload for content packages.

Confirm it loaded by checking `~/Chromatik/Logs` for `Loading package content from: …` followed by a `Package:Laserphile Audio` line.

## Gotchas

> [!CAUTION]
> Any enum used with an `EnumParameter` **must be `public`, and so must its enclosing class.** LX reflects on the enum's `values()` from another package. A package-private enum compiles cleanly and then throws `IllegalAccessException` the moment the pattern is instantiated.

- **Keep knobs in the first eight remote controls, and add the parameters in that same order.** A MIDI surface binds its eight device knobs to the first eight entries of `getRemoteControls()`, and an APC40 cannot page past the eighth. Left alone, that list is every listenable parameter in `addParameter` order, so a `BooleanParameter` or a `TriggerParameter` declared early costs a knob. Call `setRemoteControls(...)` in the constructor with the eight continuous parameters worth playing first and the buttons after them, then order the `addParameter` calls to match. The panel is drawn from `addParameter` order, a row at a time, so matching the two makes the panel read left to right in knob order with the eight knobs along its top row. `VideoPattern` is the worked example.
- **Only `public` classes are auto-discovered.** Chromatik scans the jar for public `LXPattern` (and `LXEffect`, `LXModulator`) subclasses. Keep everything else package-private: it keeps the discovered surface honest and the scan quiet.
- **The Chromatik category comes from `@LXCategory`,** not from `lx.package`. Two plugins can share a category, and `laserphile.chromatik.video`'s patterns all use `Laserphile`.
- **Don't relocate packages in the shade config** without checking what the library does at runtime. The inherited config deliberately does no relocation because JavaCPP looks its native libraries up as classpath resources by literal path.
- **Version is shared.** All modules inherit the root pom's version, so they release together. A module can declare its own `<version>` if it genuinely needs to diverge, but the default is the right one for a repo where everything ships as a set.
- **Watch the class-scan noise.** A fat dependency's optional integration classes produce benign `NoClassDefFoundError` output during Chromatik's package scan. Exclude them with shade filters in the module's own `<build>` block if it gets loud.
