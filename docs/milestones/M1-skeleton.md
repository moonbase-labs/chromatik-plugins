# M1. Skeleton plus decode-to-single-point sanity

**Goal:** A real, installable package that builds, appears in Chromatik, and proves the threading model end to end: a background decode thread fills a frame buffer while `run()` stays non-blocking. Rendering is deliberately trivial (whole model = one colour from the latest frame) so the milestone is about plumbing, not projection.

**Prerequisite:** M0 done (decode library chosen; `lx.version` known).

## Tasks

- [ ] Use the package namespace `laserphile.chromatik.video` (decided; see `../PROGRESS.md`).
- [ ] Create `pom.xml` from the LXPackage template: the three `provided` deps `com.heronarts:{lx,glx,glxstudio}` at the pinned `lx.version`; `maven.compiler.release=21`; keep the `-Pinstall` copy-to-`~/Chromatik/Packages` profile; add the chosen decode dependency (JavaCV with the `macosx-arm64` classifier, or JCodec); add `maven-shade-plugin` to bundle decode classes + natives (LX/GLX stay out; for JavaCV keep `org/bytedeco/**` paths verbatim and set `org.bytedeco.javacpp.cachedir.nosubdir=true`).
- [ ] Create `src/main/resources/lx.package` (name, author, mediaDir; keep `@...@` resource-filter tokens).
- [ ] Implement `VideoPattern extends LXPattern` with minimal parameters (`fileName`, `reload`), auto-discovered (public, non-abstract).
- [ ] Implement `FrameSource` interface + `FileVideoSource` (open, readFrame downscaled, close) using the chosen library.
- [ ] Implement `FramePipeline` with a background decode thread and a bounded ring buffer; `start()` in `onActive()`, `stop()` in `onInactive()`/`dispose()` (idempotent, bounded join, close source).
- [ ] `run(deltaMs)`: read the latest available frame non-blockingly, compute its centre or average pixel, and set every `colors[point.index]` to that colour. Hardcode play + loop for now.

## Exit criteria

- `mvn package` succeeds; `mvn install` drops the jar into `~/Chromatik/Packages`.
- `VideoPattern` shows up in Chromatik's pattern list.
- Loading a file makes the whole model flash the video's colours in time with playback.
- Deliberately missing/bad file does not freeze the UI.

## Files touched

- `pom.xml`, `src/main/resources/lx.package`
- `src/main/java/<pkg>/VideoPattern.java`, `FrameSource.java`, `FileVideoSource.java`, `FramePipeline.java`, `VideoFrame.java`

## Verification

Watch the app frame-rate meter while a file plays: it must hold steady (proves the engine thread never blocks on decode).
