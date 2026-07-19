# M2. Projection MVP

**Goal:** Replace the trivial single-colour render with the real UV projection so a recognisable video appears mapped onto any model. Projection behaves like the built-in `ImagePattern` so users find it familiar.

**Prerequisite:** M1 done (frames flowing, threading proven).

## Tasks

- [ ] Implement `ProjectionParams`: a per-frame snapshot computed on the engine thread (rotation matrix `R` from yaw/pitch/roll and its transpose, reciprocal scales, aspect factor from frame dimensions x `stretchAspect`, packed scroll offsets).
- [ ] Implement `Projector.project(frame, params, model, colors)` using the per-point maths in `../PLAN.md` (normalised coords -> inverse transform -> `(u,v)`).
- [ ] Implement wrap modes matching `ImagePattern.ImageMode`: `CLAMP` (clamp to edge), `CLIP` (outside 0..1 -> background), `TILE` (repeat), `MIRROR` (mirror-repeat).
- [ ] Implement background modes: `BLACK` (`0xFF000000`) and `CLEAR` (`0x00000000`).
- [ ] Implement `sample(argb, w, h, u, v, bilinear)` shared helper (nearest for this milestone).
- [ ] Wire the projection parameters onto `VideoPattern` (mirror `ImagePattern.Image` names): `yaw/pitch/roll`, `translateX/Y/Z`, `scale` (+ `scaleRange`, `scaleX/scaleY`), `stretchX/stretchY/stretchAspect`, `scrollX/scrollY`, `imageMode`, `backgroundMode`.
- [ ] Keep auto-play + loop on.

## Exit criteria

- A recognisable video is projected onto the model.
- Projection knobs (rotate/translate/scale/scroll/wrap/background) behave like `ImagePattern`.

## Files touched

- `src/main/java/<pkg>/Projector.java`, `ProjectionParams.java`
- `VideoPattern.java` (add projection parameters), `VideoFrame.java` (dimensions/accessors as needed)

## Verification

Install, add over a test model (ideally one with clear 2D structure), load a clip with recognisable content, and confirm orientation/position/scale controls move the image as expected. A flat 2D wall should look like the video; a 3D model should show the projected slice.
