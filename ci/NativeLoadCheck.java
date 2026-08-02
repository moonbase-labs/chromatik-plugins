import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.Loader;
import java.io.File;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Release gate for one built plugin jar. Run it against the jar itself:
 *
 * <pre>
 *   java -cp packages/chromatik-video/target/chromatik-video-0.0.1-SNAPSHOT-macos.jar \
 *        ci/NativeLoadCheck.java
 * </pre>
 *
 * Java's single-file source launcher compiles and runs this in one step, so the check needs no
 * build step, no test framework, and no dependency of its own.
 *
 * CI runs it once per platform on real hardware of that platform. That is the only way to catch
 * the failure this guards against: a jar whose bundled FFmpeg native cannot be extracted or
 * loaded on the machine it was shipped to. Everything else about the build can look perfect and
 * the plugin will still do nothing.
 *
 * Exits non-zero on the first failure, since a jar that fails any of these should not ship.
 */
public class NativeLoadCheck {

  private static final int FRAMES_TO_DECODE = 10;

  /**
   * A 2.7 KB H.264/MP4 clip, 10 frames of 64x48, committed alongside this file. Decoding it
   * proves the whole path works, not merely that the libraries loaded, and H.264 in MP4 is what
   * the plugin actually gets pointed at in the wild.
   *
   * <p>An earlier version generated its input from FFmpeg's synthetic {@code lavfi testsrc}
   * instead, which needed no file. Bytedeco's Linux builds ship without the lavfi demuxer, so
   * the check failed on Linux for a reason that had nothing to do with the jar. A fixture works
   * on every platform and exercises only the decoders the plugin needs.
   *
   * <p>Regenerate with:
   * {@code ffmpeg -f lavfi -i testsrc=size=64x48:rate=10:duration=1 -c:v libx264
   * -pix_fmt yuv420p -preset veryslow -an ci/testclip.mp4}
   */
  private static final String TEST_CLIP = "ci/testclip.mp4";

  public static void main(String[] args) throws Exception {
    System.out.println("platform: " + Loader.Detector.getPlatform());

    checkNativesLoad();
    checkJarContents();
    checkDecode();

    System.out.println("OK");
  }

  /** The per-platform risk: can JavaCPP extract the bundled natives and dlopen them here? */
  private static void checkNativesLoad() {
    Loader.load(avutil.class);
    Loader.load(avcodec.class);
    Loader.load(avformat.class);
    Loader.load(swscale.class);

    System.out.println("avutil   " + versionString(avutil.avutil_version()));
    System.out.println("avcodec  " + versionString(avcodec.avcodec_version()));
    System.out.println("avformat " + versionString(avformat.avformat_version()));
    System.out.println("swscale  " + versionString(swscale.swscale_version()));
  }

  /**
   * Chromatik finds a package by looking for lx.package at the jar root, then reflects over the
   * public LXPattern subclasses it finds. Both have to survive shading or the jar installs and
   * silently contributes nothing.
   *
   * VideoPattern is checked as a resource rather than loaded, because it extends LXPattern and
   * LX is a provided dependency: it is deliberately absent from this jar and from this classpath.
   */
  private static void checkJarContents() {
    requireResource("lx.package");
    requireResource("laserphile/chromatik/video/VideoPattern.class");

    System.out.println("lx.package and VideoPattern present");
  }

  private static void checkDecode() throws Exception {
    final File clip = new File(TEST_CLIP);
    if (!clip.isFile()) {
      throw new IllegalStateException(
        "run this from the repo root, so " + TEST_CLIP + " resolves");
    }

    int decoded = 0;

    try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip)) {
      grabber.start();

      Frame frame;
      while (decoded < FRAMES_TO_DECODE && (frame = grabber.grabImage()) != null) {
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0) {
          throw new IllegalStateException("decoded a frame with no image data");
        }
        decoded++;
      }
    }

    if (decoded < FRAMES_TO_DECODE) {
      throw new IllegalStateException(
        "decoded " + decoded + " frames, wanted " + FRAMES_TO_DECODE);
    }

    System.out.println("decoded " + decoded + " frames");
  }

  private static void requireResource(String path) {
    if (NativeLoadCheck.class.getClassLoader().getResource(path) == null) {
      throw new IllegalStateException("missing from the jar: " + path);
    }
  }

  /** FFmpeg packs its version into one int as major/minor/micro, one byte each. */
  private static String versionString(int packed) {
    return (packed >> 16 & 0xFF) + "." + (packed >> 8 & 0xFF) + "." + (packed & 0xFF);
  }
}
