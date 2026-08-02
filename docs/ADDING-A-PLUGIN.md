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

If the dependency is one the root pom already manages (the `org.bytedeco` decode stack), declare groupId and artifactId only and let the version and classifier come from `dependencyManagement`. See `packages/chromatik-video/pom.xml`.

If it's new and more than one plugin will ever want it, add it to the root pom's `<dependencyManagement>` first, then declare it unversioned here. Don't add it to the root `<dependencies>` unless every plugin genuinely needs it: that block is inherited, and FFmpeg alone is 22 MB per jar.

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

## 4. Register the module

Add one line to the root `pom.xml`:

```xml
<modules>
    <module>packages/chromatik-video</module>
    <module>packages/chromatik-audio</module>
</modules>
```

## 5. Build and install

```bash
mvn package                                  # every plugin
mvn -pl :chromatik-audio package             # just this one
mvn -Pinstall install -pl :chromatik-audio   # build it and copy into ~/Chromatik/Packages
```

Chromatik has to be **restarted** to pick up a new or rebuilt package. There is no hot reload for content packages.

Confirm it loaded by checking `~/Chromatik/Logs` for `Loading package content from: …` followed by a `Package:Laserphile Audio` line.

## Gotchas

> [!CAUTION]
> Any enum used with an `EnumParameter` **must be `public`, and so must its enclosing class.** LX reflects on the enum's `values()` from another package. A package-private enum compiles cleanly and then throws `IllegalAccessException` the moment the pattern is instantiated.

- **Only `public` classes are auto-discovered.** Chromatik scans the jar for public `LXPattern` (and `LXEffect`, `LXModulator`) subclasses. Keep everything else package-private: it keeps the discovered surface honest and the scan quiet.
- **The Chromatik category comes from `@LXCategory`,** not from `lx.package`. Two plugins can share a category, and `laserphile.chromatik.video`'s patterns all use `Laserphile`.
- **Don't relocate packages in the shade config** without checking what the library does at runtime. The inherited config deliberately does no relocation because JavaCPP looks its native libraries up as classpath resources by literal path.
- **Version is shared.** All modules inherit the root pom's version, so they release together. A module can declare its own `<version>` if it genuinely needs to diverge, but the default is the right one for a repo where everything ships as a set.
- **Watch the class-scan noise.** A fat dependency's optional integration classes produce benign `NoClassDefFoundError` output during Chromatik's package scan. Exclude them with shade filters in the module's own `<build>` block if it gets loud.
