# S0. Offscreen GL context spike

Decide how a Chromatik package gets an OpenGL context it can compile GLSL with, before any of the
plugin is built around the answer.

## Why this is a milestone rather than a first task

Chromatik offers nothing to borrow, and each of the obvious routes is closed for a different
reason:

- **`org.lwjgl:lwjgl-opengl` is not on the runtime classpath.** GLX depends on LWJGL core, bgfx,
  GLFW, NanoVG, STB, TinyFD and Assimp, all at 3.3.6, and on none of the GL module. So
  `org.lwjgl.opengl.*` does not exist unless a package brings it.
- **There is no context on Chromatik's own window.** `heronarts.glx.GLXWindow` creates it with
  `GLFW_CLIENT_API = GLFW_NO_API`, then hands the raw native handle to bgfx. On macOS that is
  Metal. There is no `GLCapabilities` to attach to and no `makeCurrent` anywhere on `GLX`.
- **bgfx cannot compile GLSL at runtime.** Its shaders are `.sc` sources compiled offline into
  per-backend `.bin` files at GLX build time, and no `shaderc` ships in the distribution. Its
  resources are also pinned to the bgfx thread, which is not the engine thread.
- **A hidden GLFW window is not available either.** Chromatik launches with
  `-XstartOnFirstThread` so GLFW can own the main thread, and GLFW may only create a window from
  that thread. Rendering happens on a frame pipeline thread, which is never it. This is the same
  constraint that already ruled out `java.awt.Robot` for screen capture.

That leaves bundling `lwjgl-opengl` and going straight to the platform's own context API. Two
things about that could not be settled by reading jars, and both would have changed the design:
whether the bundled module's native loads at all from a package jar, and whether a context can be
created off the main thread.

## Questions and answers

| question | answer |
|---|---|
| Does `lwjgl-opengl` carry a native? | Yes. `macos/arm64/org/lwjgl/opengl/liblwjgl_opengl.dylib`, resolved by literal classpath resource path. So the module needs `dist-*` profiles, and the inherited no-relocation shade config is already correct for it. |
| Does that native load from a package jar? | Yes. Reproduced Chromatik's arrangement with a child `URLClassLoader` over the built jar, parented to a loader carrying LWJGL core but no GL. `org.lwjgl.system.Library` resolves against the context class's own loader, which is the child, so it finds it. |
| Can a context be created off the main thread? | Yes on macOS, via CGL. A CGL context has no drawable attached, so there is no window, no view, and nothing AppKit wants the main thread for. Created and made current on a plain daemon thread. |
| What does the driver report? | `GL_VERSION 4.1 Metal - 90.5`, `GL_SHADING_LANGUAGE_VERSION 4.10`, renderer `Apple M4 Pro`. Comfortably above the `#version 330 core` the prologue injects. |
| Is `#define gl_FragColor <out var>` legal? | Yes, accepted by Apple's driver. This is what lets a legacy shader compile with its source byte-identical, so `#line 1` keeps compile errors pointing at the line the author wrote. |
| Does a real legacy shader compile through the prologue? | Yes. `party_blob.glsl`, which has no `#version` and writes `gl_FragColor`, compiled and rendered unmodified. |
| Does readback give usable pixels? | Yes. `glReadPixels(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)` lands as `0xAARRGGBB` on a little-endian host, which is exactly what `VideoFrame.argb` already promises. No conversion needed. |

## Decisions this settles

- **Bring our own context, per platform, without GLFW.** CGL on macOS, and by the same argument
  WGL and GLX on the others. This sidesteps the main-thread rule everywhere rather than only on
  the platforms that tolerate breaking it.
- **Bundle `lwjgl-opengl` at compile scope, pinned to whatever GLX depends on.** LWJGL core stays
  `provided`: the GL module links against core classes Chromatik has already loaded, and shading
  a second copy of `org/lwjgl/system/**` would be a duplicate-registration error per class. The
  jar assertion in CI is what holds that line.
- **The shader is a `FrameSource`.** A context belongs to one thread and `FramePipeline` already
  gives a source a thread for the whole of its life, so `open` creates the context, `grab` renders
  and reads back, and `close` tears it down. Nothing in `chromatik-core` had to change.
- **Rows are flipped on readback.** OpenGL's framebuffer origin is bottom-left and the projection
  stage samples a top-first texture, so the copy out of the readback buffer runs in reverse.

## How it landed

Both spikes ran as throwaway single-file harnesses outside the repo, in the manner of the earlier
milestones. Neither needed Chromatik running: CGL makes a context without a window, so a plain
`java` process can prove the whole path from context to compiled shader to a PNG on disk.

One thing the spike changed about the plan: `lwjgl-opengl` was expected to possibly be pure Java,
which would have made the module platform-independent and saved the four `dist-*` profiles. It is
not, so the shader plugin ships one jar per platform exactly as `chromatik-core` does.
