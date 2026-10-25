package laserphile.chromatik.touch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Whether the app this JVM runs inside can be granted the camera at all on macOS.
 *
 * <p>macOS gates the camera twice. An app signed with the hardened runtime must carry the
 * {@code com.apple.security.device.camera} entitlement, and any app must declare
 * {@code NSCameraUsageDescription} in its Info.plist, or the system denies access outright: no
 * prompt, no entry under System Settings > Privacy & Security > Camera, nothing for the user to
 * grant. FFmpeg's avfoundation input then waits forever for a first frame, which is the hang
 * {@link BoundedOpen} bounds. The shipped Chromatik.app (checked 2026-10-25, 1.2.1, team
 * 8PC3CJYX7N) has the audio-input entitlement and microphone description but neither camera one,
 * so on that build the webcam can never open and the only honest thing to do is say so at once.
 *
 * <p>The bundle is found by walking up from {@code java.home}, which the packaged app keeps at
 * {@code Chromatik.app/Contents/runtime/Contents/Home}. The entitlements and signing flags come
 * from {@code codesign}, the same tool a person would use to check. If there is no bundle (a
 * plain JDK running headless) or {@code codesign} cannot be run, nothing is concluded and the open
 * proceeds under its deadline as usual. The answer is computed once per JVM.
 */
final class MacCameraAccess {

  private static final String CAMERA_ENTITLEMENT = "com.apple.security.device.camera";
  private static final String CAMERA_USAGE_KEY = "NSCameraUsageDescription";
  private static final long CODESIGN_TIMEOUT_S = 5;

  private static final Object LOCK = new Object();
  private static boolean checked;
  private static String reason;

  private MacCameraAccess() {
  }

  /**
   * Why the camera can never be granted to this process, or null when it might be (including when
   * that cannot be determined). Only ever non-null on macOS.
   */
  static String denialReason() {
    synchronized (LOCK) {
      if (!checked) {
        checked = true;
        try {
          reason = isMac() ? denialReasonFor(enclosingAppBundle()) : null;
        } catch (Throwable ignored) {
          reason = null; // an inconclusive check must not stand in for the device's own answer
        }
      }
      return reason;
    }
  }

  /** The check itself, for a given bundle; null for no bundle or for one that might be granted. */
  static String denialReasonFor(Path bundle) throws IOException, InterruptedException {
    if (bundle == null) {
      return null;
    }

    final Path infoPlist = bundle.resolve("Contents/Info.plist");
    if (!Files.isRegularFile(infoPlist)) {
      return null; // not a bundle we can read, so nothing to conclude
    }

    final boolean describesCamera = fileContains(infoPlist, CAMERA_USAGE_KEY);

    final String signing = run("codesign", "-d", "-vv", bundle.toString());
    final boolean hardened = signing != null && signing.contains("runtime");

    final String entitlements = run("codesign", "-d", "--entitlements", "-", "--xml", bundle.toString());
    final boolean entitled = entitlements != null && entitlements.contains(CAMERA_ENTITLEMENT);

    final List<String> missing = new ArrayList<>();
    if (hardened && entitlements != null && !entitled) {
      missing.add("the " + CAMERA_ENTITLEMENT + " entitlement (it is signed with the hardened runtime)");
    }
    if (!describesCamera) {
      missing.add(CAMERA_USAGE_KEY + " in its Info.plist");
    }

    if (missing.isEmpty()) {
      return null;
    }

    return String.format(
      "this Chromatik build cannot be granted camera access: %s lacks %s. macOS denies the camera "
        + "without prompting and never lists Chromatik under System Settings > Privacy & Security > "
        + "Camera, so there is nothing to grant, and FFmpeg would otherwise wait forever for a first "
        + "frame. Webcam capture needs a Chromatik build signed with that entitlement and usage "
        + "description. (Audio input works because its entitlement is present.)",
      bundle,
      String.join(" and ", missing));
  }

  /** The .app this JVM runs inside, or null when it is not inside one. */
  static Path enclosingAppBundle() {
    final String javaHome = System.getProperty("java.home", "");
    if (javaHome.isEmpty()) {
      return null;
    }

    for (Path dir = Paths.get(javaHome); dir != null; dir = dir.getParent()) {
      final Path name = dir.getFileName();
      if (name != null
        && name.toString().endsWith(".app")
        && Files.isRegularFile(dir.resolve("Contents/Info.plist"))) {
        return dir;
      }
    }
    return null;
  }

  private static boolean fileContains(Path file, String needle) throws IOException {
    // A plist can be XML or binary; the key name is ASCII in both, so a byte search covers either.
    return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(needle);
  }

  /** Stdout and stderr of a command, or null if it could not be run or did not finish in time. */
  private static String run(String... command) throws IOException, InterruptedException {
    final Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException notRunnable) {
      return null;
    }

    try {
      final byte[] output = process.getInputStream().readAllBytes();
      if (!process.waitFor(CODESIGN_TIMEOUT_S, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      return process.exitValue() == 0 ? new String(output, StandardCharsets.UTF_8) : null;
    } finally {
      process.destroyForcibly();
    }
  }

  private static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
  }
}
