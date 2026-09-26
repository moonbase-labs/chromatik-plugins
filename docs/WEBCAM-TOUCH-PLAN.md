# Webcam Touch Plan

This plan implements a Java-first live webcam pattern in Chromatik that preserves OSC output while keeping a simple image-mask path.

## Scope

- New plugin module: `packages/chromatik-touch`
- New pattern: `Laserphile -> Touch Camera`
- Live source path based on `FrameSource` + `FramePipeline` from `chromatik-core`
- Built-in image processing in Java: baseline subtraction, threshold, optional circular mask
- Built-in blob extraction in Java: connected-component pass + polar segmentation
- Optional OSC output for detected touch blobs and per-segment summaries

## Phased rollout

1. Phase 1: Working vertical slice
- Capture webcam frames in a background thread.
- Process each frame into a thresholded mask image.
- Detect blobs and compute centroid, angle, distance, segment.
- Emit OSC events (toggleable), project mask image onto LEDs.

2. Phase 2: Robustness and UX
- Add camera input presets and better validation per OS backend.
- Add smoothing/debounce for blob IDs and segment values.
- Improve diagnostics for permission/device-open failures.

3. Phase 3: Touch semantics
- Add segment-level aggregation outputs tuned for your control workflow.
- Add alternate output messages (raw points, normalized polar, centroid tracks).
- Add optional calibration save/load.

## Current implementation notes

- Uses FFmpeg webcam capture through JavaCV (`FFmpegFrameGrabber`), same dependency family as existing plugins.
- Keeps all processing off the LX engine thread by doing vision work inside the capture source.
- Uses a simple OSC UDP sender with minimal OSC 1.0 message encoding.
- Includes controls for threshold, minimum blob area, segment count, circular mask radius, OSC host/port/prefix, and one-shot calibration.

## Acceptance checks

- Pattern appears in Chromatik under Laserphile category.
- With a camera available, mask image updates live and responds to calibration/threshold controls.
- Blob count and segment OSC messages are transmitted when OSC is enabled.
- Plugin builds with `mvn -pl :chromatik-touch -am package`.
