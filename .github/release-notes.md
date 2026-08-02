Play video, or your live desktop, on LEDs in [Chromatik](https://chromatik.co/). No build tools needed, and nothing to install besides Chromatik itself.

## Download

Two files: the **Core** package for your computer, plus whichever pattern you want. Core carries the video engine that both patterns share, so it is always needed.

| | File |
|---|---|
| **Core**, for a Mac (Apple Silicon or Intel) | `chromatik-core-{{VERSION}}-macos.jar` |
| **Core**, for Windows | `chromatik-core-{{VERSION}}-windows.jar` |
| **Core**, for Linux (Intel/AMD) | `chromatik-core-{{VERSION}}-linux-x86_64.jar` |
| **Core**, for Linux (ARM, e.g. Raspberry Pi) | `chromatik-core-{{VERSION}}-linux-arm64.jar` |
| **Video**, plays a video file | `chromatik-video-{{VERSION}}.jar` |
| **Screen Capture**, mirrors your desktop | `chromatik-screen-{{VERSION}}.jar` |

The Mac Core file works on both Apple Silicon and Intel, so there is nothing to check first. The two pattern files are the same on every platform.

## Install

1. Download the **Core** file for your computer from **Assets** below, plus **Video**, **Screen Capture**, or both.
2. **Drag each onto the Chromatik window.** Chromatik installs them for you.
3. In Chromatik's **CONTENT** tab, click **Reload Package Content**.

To confirm it worked, add a pattern to a channel and look for **Laserphile → Video** or **Laserphile → Screen Capture**.

Install a pattern without Core and it will appear in the list and then refuse to load, saying so. Install Core and restart.

<details>
<summary>Prefer to place the files yourself?</summary>

Drop the `.jar` files into your Chromatik packages folder and restart the app.

| | |
|---|---|
| macOS | `~/Chromatik/Packages` |
| Windows | `C:\Users\<you>\Chromatik\Packages` |
| Linux | `~/Chromatik/Packages` |

Chromatik creates that folder the first time it runs.
</details>

<details>
<summary>Upgrading from v0.1.0? Delete the old file first.</summary>

v0.1.0 shipped as a single `chromatik-video-0.1.0-<platform>.jar` carrying everything. It is not replaced by any of the files above, so it sits alongside them and Chromatik loads both: two packages claiming the same name, and every class in one duplicated in the other, which fills the log with errors.

Delete `chromatik-video-0.1.0-*.jar` from your packages folder before installing these, then restart Chromatik. Your saved projects are unaffected: the pattern's name and all its controls are unchanged.
</details>

## Using it

Add **Laserphile → Video**, then click **Browse** and pick a video file. Or add **Laserphile → Screen Capture** to put your desktop on the LEDs live. Either way the frame is projected onto your model's 3D points, so it works on domes, sculptures and strips, not just grids.

Videos kept anywhere under your `Chromatik` folder are saved as relative paths, so a project you share with someone else still finds them.

Screen capture needs the operating system's permission, granted to Chromatik itself. On macOS that is **System Settings → Privacy & Security → Screen & System Audio Recording**, and Chromatik has to be restarted afterwards.

## Verified

Every Core file here was loaded on real hardware of its platform before release: FFmpeg natives loaded and frames decoded on macOS arm64, macOS x86_64, Windows x86_64, Linux x86_64 and Linux arm64. Both pattern files were checked on every one of those platforms too.

That check covers decoding. Installing and playing end to end inside Chromatik is exercised on macOS, so if something looks wrong on another platform please [open an issue](https://github.com/moonbase-labs/chromatik-plugins/issues).

## Checksums

`SHA256SUMS` lists a checksum for every file. To verify a download:

```bash
shasum -a 256 -c SHA256SUMS --ignore-missing
```

## Licence

The source in this repository is **MIT**, and the two pattern files bundle nothing else, so they are MIT in full.

The **Core** files additionally bundle FFmpeg binaries from [Bytedeco's JavaCPP presets](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg), which are **LGPL 2.1 or later**.

If you redistribute a Core file, the LGPL applies to the FFmpeg portions: keep them LGPL, and let recipients replace them. They can be replaced by swapping the `org/bytedeco/ffmpeg/**` entries inside the `.jar`. FFmpeg source is available from [Bytedeco](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg) and [ffmpeg.org](https://ffmpeg.org/download.html), and the LGPL 2.1 text is at [gnu.org](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).

These are the default LGPL FFmpeg builds, not the `-gpl` variants.
