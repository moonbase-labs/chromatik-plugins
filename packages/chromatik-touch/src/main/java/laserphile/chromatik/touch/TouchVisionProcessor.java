package laserphile.chromatik.touch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import laserphile.chromatik.core.VideoFrame;

/**
 * Lightweight image-mask and blob extraction pipeline.
 *
 * The processor keeps only integer math and a connected-components pass so it can run in the
 * capture thread with predictable cost and no OpenCV dependency.
 */
final class TouchVisionProcessor {

  private int width = -1;
  private int height = -1;

  private int[] baselineGray;
  private int[] gray;
  private byte[] binary;
  private boolean[] visited;
  private int[] queue;

  record ProcessedFrame(VideoFrame frame, List<TouchBlob> blobs) {
  }

  ProcessedFrame process(VideoFrame captured, TouchVisionConfig config) {
    final int pixelCount = captured.width * captured.height;
    ensureCapacity(captured.width, captured.height, pixelCount);

    toGray(captured.argb, this.gray);

    if (config.takeCalibrationRequest()) {
      System.arraycopy(this.gray, 0, this.baselineGray, 0, pixelCount);
    }

    final int threshold = clamp(config.threshold, 0, 255);
    final double gain = clamp(config.currentGain, 0.01, 4);
    final double centerX = (captured.width - 1) * 0.5;
    final double centerY = (captured.height - 1) * 0.5;
    final double maxRadiusPx = Math.max(1, Math.min(captured.width, captured.height) * 0.5);
    final double maskRadiusPx = maxRadiusPx * clamp(config.maskRadiusNormalized, 0.05, 1);
    final double maskRadiusSq = maskRadiusPx * maskRadiusPx;

    final int[] processedArgb = new int[pixelCount];

    for (int y = 0; y < captured.height; y++) {
      for (int x = 0; x < captured.width; x++) {
        final int index = y * captured.width + x;

        int scaledCurrent = (int) Math.round(this.gray[index] * gain);
        scaledCurrent = clamp(scaledCurrent, 0, 255);

        int difference = config.invertDifference
          ? this.baselineGray[index] - scaledCurrent
          : scaledCurrent - this.baselineGray[index];

        difference = clamp(difference, 0, 255);

        if (config.circularMaskEnabled) {
          final double dx = x - centerX;
          final double dy = y - centerY;
          if ((dx * dx + dy * dy) > maskRadiusSq) {
            difference = 0;
          }
        }

        final int value = difference >= threshold ? 255 : 0;
        this.binary[index] = (byte) value;
        processedArgb[index] = 0xFF000000 | (value << 16) | (value << 8) | value;
      }
    }

    final List<TouchBlob> blobs = detectBlobs(
      captured.width,
      captured.height,
      Math.max(1, config.minBlobArea),
      Math.max(1, config.segmentCount),
      centerX,
      centerY,
      maskRadiusPx);

    if (config.overlayBlobs) {
      drawCenterMarker(processedArgb, captured.width, captured.height, (int) centerX, (int) centerY);

      for (TouchBlob blob : blobs) {
        drawBlobMarker(
          processedArgb,
          captured.width,
          captured.height,
          Math.round(blob.xPixels()),
          Math.round(blob.yPixels()));
      }
    }

    return new ProcessedFrame(
      new VideoFrame(processedArgb, captured.width, captured.height, captured.mediaTimeMs),
      blobs);
  }

  private void ensureCapacity(int width, int height, int pixelCount) {
    if (this.width == width && this.height == height && this.baselineGray != null) {
      return;
    }

    this.width = width;
    this.height = height;

    this.baselineGray = new int[pixelCount];
    this.gray = new int[pixelCount];
    this.binary = new byte[pixelCount];
    this.visited = new boolean[pixelCount];
    this.queue = new int[pixelCount];
  }

  private static void toGray(int[] argb, int[] gray) {
    for (int i = 0; i < argb.length; i++) {
      final int pixel = argb[i];
      final int r = (pixel >>> 16) & 0xFF;
      final int g = (pixel >>> 8) & 0xFF;
      final int b = pixel & 0xFF;

      gray[i] = (77 * r + 150 * g + 29 * b) >>> 8;
    }
  }

  private List<TouchBlob> detectBlobs(
    int width,
    int height,
    int minBlobArea,
    int segmentCount,
    double centerX,
    double centerY,
    double maxRadiusPx) {

    Arrays.fill(this.visited, false);

    final List<TouchBlob> blobs = new ArrayList<>();
    final double segmentWidth = 360.0 / segmentCount;

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int startIndex = y * width + x;

        if ((this.binary[startIndex] & 0xFF) == 0 || this.visited[startIndex]) {
          continue;
        }

        int queueHead = 0;
        int queueTail = 0;

        this.queue[queueTail++] = startIndex;
        this.visited[startIndex] = true;

        int area = 0;
        long xSum = 0;
        long ySum = 0;

        while (queueHead < queueTail) {
          final int index = this.queue[queueHead++];

          final int pixelY = index / width;
          final int pixelX = index - pixelY * width;

          area++;
          xSum += pixelX;
          ySum += pixelY;

          if (pixelX > 0) {
            queueTail = enqueueIfForeground(index - 1, queueTail);
          }
          if (pixelX + 1 < width) {
            queueTail = enqueueIfForeground(index + 1, queueTail);
          }
          if (pixelY > 0) {
            queueTail = enqueueIfForeground(index - width, queueTail);
          }
          if (pixelY + 1 < height) {
            queueTail = enqueueIfForeground(index + width, queueTail);
          }
        }

        if (area < minBlobArea) {
          continue;
        }

        final float xMean = (float) (xSum / (double) area);
        final float yMean = (float) (ySum / (double) area);

        final double dx = xMean - centerX;
        final double dy = yMean - centerY;

        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) {
          angle += 360;
        }

        final int segment = Math.min(segmentCount - 1, (int) (angle / segmentWidth));

        final double distance = Math.hypot(dx, dy);
        final float distanceNormalized = (float) Math.min(1, distance / Math.max(1, maxRadiusPx));

        final float normalizedX = width < 2 ? 0 : (float) ((xMean / (width - 1)) * 2 - 1);
        final float normalizedY = height < 2 ? 0 : (float) ((yMean / (height - 1)) * 2 - 1);

        blobs.add(new TouchBlob(
          segment,
          xMean,
          yMean,
          normalizedX,
          normalizedY,
          (float) angle,
          distanceNormalized,
          area));
      }
    }

    return blobs;
  }

  private int enqueueIfForeground(int index, int queueTail) {
    if (this.visited[index]) {
      return queueTail;
    }

    if ((this.binary[index] & 0xFF) == 0) {
      return queueTail;
    }

    this.visited[index] = true;
    this.queue[queueTail] = index;
    return queueTail + 1;
  }

  private static void drawCenterMarker(int[] argb, int width, int height, int cx, int cy) {
    drawDisc(argb, width, height, cx, cy, 4, 0xFF00FF00);
  }

  private static void drawBlobMarker(int[] argb, int width, int height, int cx, int cy) {
    drawDisc(argb, width, height, cx, cy, 6, 0xFFFF4040);
  }

  private static void drawDisc(int[] argb, int width, int height, int cx, int cy, int radius, int color) {
    final int r2 = radius * radius;

    final int minY = Math.max(0, cy - radius);
    final int maxY = Math.min(height - 1, cy + radius);
    final int minX = Math.max(0, cx - radius);
    final int maxX = Math.min(width - 1, cx + radius);

    for (int y = minY; y <= maxY; y++) {
      final int dy = y - cy;
      for (int x = minX; x <= maxX; x++) {
        final int dx = x - cx;
        if (dx * dx + dy * dy <= r2) {
          argb[y * width + x] = color;
        }
      }
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
