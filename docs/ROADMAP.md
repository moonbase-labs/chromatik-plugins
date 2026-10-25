# 🗺️ Roadmap

- [x] **M0** Decode spike. Benchmarked JavaCV/FFmpeg on real footage: ~4,450 fps at 384×216, against the ~60 needed.
- [x] **M1** Skeleton. Package loads in-app, decode thread runs, engine stays non-blocking.
- [x] **M2** Projection MVP. Full UV projection, all four wrap modes, both background modes, nearest and bilinear.
- [x] **M3** Transport. Playback clock, play/pause, loop, speed, seek and scrub, ring buffer with back-pressure and a real drop policy.
- [x] **M4** Screen capture. A `Screen Capture` pattern of its own, on FFmpeg's per-OS capture device, behind the existing `FrameSource` seam with single-slot live buffering.
- [x] **M5** Polish. BT.709 colour-space correction, `Gamma`, working-resolution downscale, recoverable decode errors, a one-click demo project, and an uber-jar down from 2,314 classes to 473. (Per-OS build profiles landed early, with the release pipeline. Frame pooling was dropped: the downscale already keeps frames small enough that it earned nothing.)

The Shader pattern has its own run, numbered separately because it is a different feature rather than a later stage of the same one:

- [x] **S0** Offscreen context spike. Established that Chromatik offers no OpenGL to borrow, and that CGL makes a context off the main thread with no window. See [`docs/milestones/shader/S0-context-spike.md`](milestones/shader/S0-context-spike.md).
- [x] **S1** Skeleton. The shader as a `FrameSource`, reusing the pipeline and the projection controls unchanged.
- [x] **S2** Loading. Dialect normalisation, entry-point detection, and hot reload that survives a broken save.
- [x] **S3** Uniforms. A control per declared uniform, `@range` annotations, and a device panel of its own so they can appear at all.
- [x] **S4** Linux, and CI. GLX onto a pbuffer, plus a release gate for the shader jar's shape.
- [ ] **S5** Windows. Needs a real window to hang a device context on, which through LWJGL's bindings means a registered window class, a native window procedure and a hand-assembled struct. Unwritten rather than half-written: it reports itself unsupported, and the other three platforms are unaffected.

Design decisions, open questions, and per-milestone detail live in [`docs/`](): [`PLAN.md`](PLAN.md) is the source of truth, [`PROGRESS.md`](PROGRESS.md) tracks state and carries the decisions log.
