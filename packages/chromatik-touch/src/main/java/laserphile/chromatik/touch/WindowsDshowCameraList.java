package laserphile.chromatik.touch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.bytedeco.ffmpeg.avutil.Callback_Pointer_int_String_Pointer;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/**
 * Enumerates Windows DirectShow video device names by asking FFmpeg to list devices.
 */
final class WindowsDshowCameraList {

  private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");

  private WindowsDshowCameraList() {
  }

  static String[] listVideoDevices() {
    final String osName = System.getProperty("os.name", "").toLowerCase();
    if (!osName.contains("win")) {
      return new String[0];
    }

    // Use the best tooling that this machine supports. Shell-based device discovery is
    // generally safer during hot-plug on Windows, and FFmpeg probing is the fallback.
    final String[] shellNames = listViaPowerShell();
    if (shellNames.length > 0) {
      return shellNames;
    }

    return listViaFfmpeg();
  }

  private static String[] listViaFfmpeg() {

    final StringBuilder logs = new StringBuilder(4096);

    FFmpegFrameGrabber grabber = null;
    int previousLogLevel = 0;
    boolean callbackInstalled = false;
    Callback_Pointer_int_String_Pointer captureCallback = null;

    try {
      captureCallback = new Callback_Pointer_int_String_Pointer() {
        @Override
        public void call(Pointer ptr, int level, String message, Pointer vl) {
          if (message != null) {
            logs.append(message);
          }
          avutil.av_log_default_callback(ptr, level, message, vl);
        }
      };

      previousLogLevel = avutil.av_log_get_level();
      avutil.av_log_set_level(avutil.AV_LOG_INFO);
      avutil.av_log_set_callback(captureCallback);
      callbackInstalled = true;

      grabber = new FFmpegFrameGrabber("dummy");
      grabber.setFormat("dshow");
      grabber.setOption("list_devices", "true");

      try {
        grabber.start();
      } catch (Exception ignored) {
        // Expected: listing devices logs then fails to open dummy input.
      }
    } catch (Throwable ignored) {
      return new String[0];
    } finally {
      if (callbackInstalled) {
        try {
          avutil.av_log_set_callback(new Callback_Pointer_int_String_Pointer() {
            @Override
            public void call(Pointer ptr, int level, String message, Pointer vl) {
              avutil.av_log_default_callback(ptr, level, message, vl);
            }
          });
          avutil.av_log_set_level(previousLogLevel);
        } catch (Throwable ignored) {
        }
      }

      if (grabber != null) {
        try {
          grabber.release();
        } catch (Exception ignored) {
        }
      }
    }

    final String output = logs.toString();
    return parseVideoDeviceNames(output);
  }

  private static String[] parseVideoDeviceNames(String output) {
    final Set<String> names = new LinkedHashSet<>();
    String pendingName = null;

    for (String line : output.split("\\R")) {
      final String lowered = line.toLowerCase();
      final Matcher matcher = QUOTED_NAME.matcher(line);
      if (matcher.find()) {
        pendingName = matcher.group(1).trim();

        // Newer FFmpeg builds may log '"Device Name" (video)' on one line.
        if (!pendingName.isBlank() && lowered.contains("(video")) {
          names.add(pendingName);
          pendingName = null;
        }
        continue;
      }

      if (pendingName == null || pendingName.isBlank()) {
        continue;
      }

      if (lowered.contains("(video")) {
        names.add(pendingName);
        pendingName = null;
      } else if (lowered.contains("(audio")) {
        pendingName = null;
      }
    }

    return names.toArray(new String[0]);
  }

  private static String[] listViaPowerShell() {
    for (String shell : List.of("pwsh", "powershell")) {
      final String[] names = listViaPowerShell(shell);
      if (names.length > 0) {
        return names;
      }
    }
    return new String[0];
  }

  private static String[] listViaPowerShell(String shell) {
    final List<String> command = List.of(
      shell,
      "-NoProfile",
      "-Command",
      "$cams = Get-CimInstance Win32_PnPEntity | Where-Object { "
        + "($_.PNPClass -eq 'Camera') -or ($_.Service -eq 'usbvideo') "
        + "} | Select-Object -ExpandProperty Name -Unique; "
        + "$cams");

    final Set<String> names = new LinkedHashSet<>();

    try {
      final Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .start();

      try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          final String trimmed = line.trim();
          if (!trimmed.isBlank() && !trimmed.startsWith("PS ")) {
            names.add(trimmed);
          }
        }
      }

      final boolean exited = process.waitFor(2, TimeUnit.SECONDS);
      if (!exited) {
        process.destroyForcibly();
        return new String[0];
      }

      if (process.exitValue() != 0) {
        return new String[0];
      }
    } catch (Throwable ignored) {
      return new String[0];
    }

    return names.toArray(new String[0]);
  }
}
