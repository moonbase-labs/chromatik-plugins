package laserphile.chromatik.touch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import heronarts.lx.LX;

/**
 * Minimal OSC over UDP publisher for touch blobs.
 *
 * The sender stays intentionally small: one packet per message, no bundle support, and only int,
 * float and string argument types. That keeps this plugin independent of non-LX OSC libraries.
 */
final class OscTouchPublisher implements AutoCloseable {

  private DatagramSocket socket;
  private InetAddress address;
  private String host = "";
  private int port = -1;
  private String prefix = "/touch";

  private boolean reportedFailure = false;

  void publish(TouchVisionConfig config, List<TouchBlob> blobs) {
    if (!config.oscEnabled) {
      this.reportedFailure = false;
      return;
    }

    try {
      ensureDestination(config.oscHost, config.oscPort, config.oscPrefix);

      send(this.prefix + "/count", blobs.size());

      final int segmentCount = Math.max(1, config.segmentCount);
      final float[] segmentDistanceSums = new float[segmentCount];
      final int[] segmentCounts = new int[segmentCount];

      for (int i = 0; i < blobs.size(); i++) {
        final TouchBlob blob = blobs.get(i);

        send(
          this.prefix + "/blob",
          i,
          blob.segment(),
          blob.angleDegrees(),
          blob.distanceNormalized(),
          blob.normalizedX(),
          blob.normalizedY(),
          blob.areaPixels());

        if (blob.segment() >= 0 && blob.segment() < segmentCount) {
          segmentDistanceSums[blob.segment()] += blob.distanceNormalized();
          segmentCounts[blob.segment()]++;
        }
      }

      for (int segment = 0; segment < segmentCount; segment++) {
        if (segmentCounts[segment] == 0) {
          continue;
        }

        final float avgDistance = segmentDistanceSums[segment] / segmentCounts[segment];
        send(this.prefix + "/segment", segment, avgDistance, segmentCounts[segment]);
      }

      this.reportedFailure = false;
    } catch (Exception failure) {
      if (!this.reportedFailure) {
        this.reportedFailure = true;
        LX.log("[LaserphileTouch] OSC publish failed: " + failure.getMessage());
      }
    }
  }

  private void ensureDestination(String host, int port, String prefix) throws Exception {
    final String resolvedHost = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
    final int resolvedPort = Math.max(1, Math.min(65535, port));
    final String resolvedPrefix = normalisedPrefix(prefix);

    if (this.socket == null) {
      this.socket = new DatagramSocket();
    }

    if (!resolvedHost.equals(this.host) || resolvedPort != this.port) {
      this.address = InetAddress.getByName(resolvedHost);
      this.host = resolvedHost;
      this.port = resolvedPort;
    }

    this.prefix = resolvedPrefix;
  }

  private void send(String address, Object... args) throws Exception {
    final byte[] payload = encodeMessage(address, args);
    final DatagramPacket packet = new DatagramPacket(payload, payload.length, this.address, this.port);
    this.socket.send(packet);
  }

  private static byte[] encodeMessage(String address, Object[] args) throws Exception {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
    final DataOutputStream out = new DataOutputStream(bytes);

    writePaddedString(bytes, address);

    final StringBuilder tags = new StringBuilder(",");
    for (Object arg : args) {
      if (arg instanceof Integer) {
        tags.append('i');
      } else if (arg instanceof Float || arg instanceof Double) {
        tags.append('f');
      } else if (arg instanceof String) {
        tags.append('s');
      } else {
        throw new IllegalArgumentException("unsupported OSC type: " + arg.getClass().getName());
      }
    }

    writePaddedString(bytes, tags.toString());

    for (Object arg : args) {
      if (arg instanceof Integer value) {
        out.writeInt(value);
      } else if (arg instanceof Float value) {
        out.writeFloat(value);
      } else if (arg instanceof Double value) {
        out.writeFloat(value.floatValue());
      } else if (arg instanceof String value) {
        writePaddedString(bytes, value);
      }
    }

    out.flush();
    return bytes.toByteArray();
  }

  private static void writePaddedString(ByteArrayOutputStream out, String value) {
    final byte[] data = value.getBytes(StandardCharsets.UTF_8);
    out.write(data, 0, data.length);
    out.write(0);

    while ((out.size() & 0x3) != 0) {
      out.write(0);
    }
  }

  private static String normalisedPrefix(String value) {
    if (value == null || value.isBlank()) {
      return "/touch";
    }

    final String trimmed = value.trim();
    return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  @Override
  public void close() {
    if (this.socket != null) {
      this.socket.close();
      this.socket = null;
    }
  }
}
