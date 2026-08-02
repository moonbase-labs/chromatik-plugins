import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.Loader;
import java.io.File;
import java.util.jar.JarFile;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Release gate for a built release. The core jar goes on the classpath, because that is the one
 * carrying the natives, and every plugin jar is named as an argument:
 *
 * <pre>
 *   java -cp packages/chromatik-core/target/chromatik-core-0.1.0-SNAPSHOT-macos.jar \
 *        ci/NativeLoadCheck.java \
 *        packages/chromatik-video/target/chromatik-video-0.1.0-SNAPSHOT.jar \
 *        packages/chromatik-screen/target/chromatik-screen-0.1.0-SNAPSHOT.jar
 * </pre>
 *
 * Java's single-file source launcher compiles and runs this in one step, so the check needs no
 * build step, no test framework, and no dependency of its own. Compiling against the classpath is
 * itself part of the gate: it is what caught the core jar being trimmed too far, when dropping
 * JavaCPP's tools package took InfoMapper with it and this file stopped compiling.
 *
 * CI runs it once per platform on real hardware of that platform. That is the only way to catch
 * the failure this guards against: a jar whose bundled FFmpeg native cannot be extracted or
 * loaded on the machine it was shipped to. Everything else about the build can look perfect and
 * the plugin will still do nothing.
 *
 * Plugin jars are opened directly rather than read off the classpath. A classpath lookup returns
 * the first match and would happily report every plugin healthy on the strength of one valid jar,
 * which is exactly the mistake worth catching when a release ships several.
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
    checkCorePackage();

    for (String pluginJar : args) {
      checkPluginJar(pluginJar);
    }

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
   * Chromatik finds a package by looking for lx.package at the jar root, so the core jar has to
   * carry one or it installs as nothing at all. Its shared classes have to survive shading too,
   * since every plugin resolves them from here.
   */
  private static void checkCorePackage() {
    requireResource("lx.package");
    requireResource("laserphile/chromatik/core/FramePipeline.class");
    requireResource("laserphile/chromatik/core/ProjectionControls.class");

    System.out.println("core: lx.package and the shared classes present");
  }

  /**
   * Each plugin jar must be a package in its own right and must carry its pattern.
   *
   * Two things are worth failing on beyond the obvious. A plugin that has quietly re-bundled the
   * decode stack, by taking its dependency at compile scope instead of provided, still works and
   * still passes every other check; the only visible symptom is the jar being enormous and
   * duplicating every one of the core's classes once installed alongside it. And a plugin whose
   * pattern class went missing in shading installs and contributes nothing.
   */
  private static void checkPluginJar(String path) throws Exception {
    final File jar = new File(path);

    if (!jar.isFile()) {
      throw new IllegalStateException("no such plugin jar: " + path);
    }

    boolean hasPackageFile = false;
    boolean hasPattern = false;
    int bundledDecodeClasses = 0;

    try (JarFile contents = new JarFile(jar)) {
      for (String entry : contents.stream().map(java.util.zip.ZipEntry::getName).toList()) {
        if (entry.equals("lx.package")) {
          hasPackageFile = true;
        } else if (entry.startsWith("org/bytedeco/")) {
          bundledDecodeClasses++;
        } else if (entry.startsWith("laserphile/chromatik/") && entry.endsWith("Pattern.class")) {
          hasPattern = true;
        }
      }
    }

    if (!hasPackageFile) {
      throw new IllegalStateException("no lx.package at the root of " + jar.getName());
    }

    if (!hasPattern) {
      throw new IllegalStateException("no pattern class in " + jar.getName());
    }

    if (bundledDecodeClasses > 0) {
      throw new IllegalStateException(String.format(
        "%s bundles %d org/bytedeco entries. Its dependency on chromatik-core should be provided, "
          + "not compile, or it will duplicate every class the core package registers.",
        jar.getName(), bundledDecodeClasses));
    }

    System.out.printf("%s: package, pattern, and no bundled decode stack%n", jar.getName());
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
