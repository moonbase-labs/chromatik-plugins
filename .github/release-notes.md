Play video on LEDs, in [Chromatik](https://chromatik.co/). No build tools needed, and nothing to install besides Chromatik itself.

## Download

| Your computer | File |
|---|---|
| **Mac** (any, Apple Silicon or Intel) | `chromatik-video-{{VERSION}}-macos.jar` |
| **Windows** | `chromatik-video-{{VERSION}}-windows.jar` |
| Linux (Intel/AMD) | `chromatik-video-{{VERSION}}-linux-x86_64.jar` |
| Linux (ARM, e.g. Raspberry Pi) | `chromatik-video-{{VERSION}}-linux-arm64.jar` |

The Mac file works on both Apple Silicon and Intel, so there is nothing to check first.

## Install

1. Download the file for your computer from **Assets** below.
2. **Drag it onto the Chromatik window.** Chromatik installs it for you.
3. In Chromatik's **CONTENT** tab, click **Reload Package Content**.

That's it. To confirm it worked, add a pattern to a channel and look for **Laserphile → Video**.

<details>
<summary>Prefer to place the file yourself?</summary>

Drop the `.jar` into your Chromatik packages folder and restart the app.

| | |
|---|---|
| macOS | `~/Chromatik/Packages` |
| Windows | `C:\Users\<you>\Chromatik\Packages` |
| Linux | `~/Chromatik/Packages` |

Chromatik creates that folder the first time it runs.
</details>

## Using it

Add the pattern (**Laserphile → Video**), then click **Browse** and pick a video file. The pattern projects each frame onto your model's 3D points, so it works on domes, sculptures and strips, not just grids.

Videos kept anywhere under your `Chromatik` folder are saved as relative paths, so a project you share with someone else still finds them.

## Verified

Every file here was loaded on real hardware of its platform before release: FFmpeg natives loaded and frames decoded on macOS arm64, macOS x86_64, Windows x86_64, Linux x86_64 and Linux arm64.

That check covers decoding. Installing and playing end to end inside Chromatik is regularly exercised on macOS only, so if something looks wrong on another platform please [open an issue](https://github.com/moonbase-labs/chromatik-plugins/issues).

<details>
<summary>macOS: if the pattern loads but no video plays</summary>

macOS tags files downloaded through a browser, which can stop the bundled FFmpeg libraries from loading. To clear the tag:

```bash
xattr -dr com.apple.quarantine ~/Chromatik/Packages/chromatik-video-{{VERSION}}-macos.jar
```

Then restart Chromatik.
</details>

## Checksums

`SHA256SUMS` lists a checksum for every file. To verify a download:

```bash
shasum -a 256 -c SHA256SUMS --ignore-missing
```

## Licence

The source in this repository is **MIT**. These `.jar` files additionally bundle FFmpeg binaries from [Bytedeco's JavaCPP presets](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg), which are **LGPL 2.1 or later**.

If you redistribute one of these files, the LGPL applies to the FFmpeg portions: keep them LGPL, and let recipients replace them. They can be replaced by swapping the `org/bytedeco/ffmpeg/**` entries inside the `.jar`. FFmpeg source is available from [Bytedeco](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg) and [ffmpeg.org](https://ffmpeg.org/download.html), and the LGPL 2.1 text is at [gnu.org](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).

These are the default LGPL FFmpeg builds, not the `-gpl` variants.
