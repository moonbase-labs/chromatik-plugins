# M0. Decode spike and decision

**Goal:** Pick the decode library (JavaCV/FFmpeg vs JCodec) from real measurements, not assumptions, and clear the small unknowns that block the pom (installed LX version, file-picker parameter behaviour). Output is a decision plus evidence, not shippable code.

**Prerequisite:** Environment setup complete (JDK 21 + Maven present; Chromatik app installed). See `../PLAN.md`.

## Tasks

- [ ] Build a throwaway harness (a scratch `main()`, not part of the shipped package) that opens a video file with each candidate, decodes frames to an ARGB `int[]`, and downscales to a working resolution (longest side ~384).
- [ ] Gather a small set of the user's **real** footage covering the formats they actually care about (container + codec).
- [ ] For each library x file, record: opens? / decode fps at native / decode fps at working res / CPU cores used / peak heap + native memory / time-to-first-frame / random-seek latency / jar + native size delta.
- [ ] Stand up a trivial screen-capture loop for each backend: JavaCV device input (`avfoundation` on macOS) vs `java.awt.Robot.createScreenCapture`. Record fps and latency.
- [ ] Eyeball a colour-bars clip to sanity-check limited-range vs full-range and gamma.
- [ ] Confirm the installed Chromatik/LX version to pin `lx.version` (record in `../PROGRESS.md`).
- [ ] Confirm the file-picker parameter behaviour: does a bare `StringParameter` get a browse button in the auto-generated panel, or is there a dedicated file/path parameter subtype `ImagePattern` uses?

## Decision gates (in order)

1. **Opens the user's real footage.** JCodec is roughly H.264 baseline / MP4 only (no HEVC/ProRes/VP9/AV1). If the footage is not covered, JavaCV wins by default.
2. Sustained decode fps at working resolution meets target (>= 30, ideally >= 60) with headroom while the engine also runs.
3. Screen-capture support (JavaCV is near-free via device inputs; JCodec has none, forcing the Robot path).
4. Footprint acceptable (JavaCV FFmpeg natives are large; JCodec is ~1 MB).
5. Seek latency acceptable for scrubbing.

**Working hypothesis (not a decision):** JavaCV wins on coverage, performance, and near-free screen capture; the cost is jar size and per-platform distribution. JCodec is only viable if footage is strictly H.264/MP4 and a tiny footprint is a hard requirement.

## Exit criteria

- A metrics table for both libraries on real footage.
- A working decode-to-`int[]` proof for the chosen library.
- Decision recorded in `../PROGRESS.md` (decisions log) with the numbers behind it.
- `lx.version` and the file-picker parameter approach recorded.

## Files touched

- Scratch harness only (throwaway, outside the package tree, not committed to `src/main`).
