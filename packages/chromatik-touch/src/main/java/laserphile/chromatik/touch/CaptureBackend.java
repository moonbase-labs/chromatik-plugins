package laserphile.chromatik.touch;

import java.util.Locale;

enum CaptureBackend {
  AUTO,
  VIDEOINPUT,
  FFMPEG;

  static final String[] OPTIONS = { "Auto", "VideoInput", "FFmpeg" };

  static int defaultOption() {
    return isWindows() ? 1 : 0;
  }

  static CaptureBackend fromIndex(int index) {
    return switch (Math.max(0, Math.min(index, OPTIONS.length - 1))) {
      case 1 -> VIDEOINPUT;
      case 2 -> FFMPEG;
      default -> AUTO;
    };
  }

  private static boolean isWindows() {
    final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return osName.contains("win");
  }
}