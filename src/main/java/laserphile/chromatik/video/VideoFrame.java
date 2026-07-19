package laserphile.chromatik.video;

/**
 * One decoded video frame as packed ARGB pixels (0xAARRGGBB), row-major, top-left origin.
 * Each frame owns its own array, so frames can be handed between threads without aliasing.
 */
final class VideoFrame {

  final int[] argb;
  final int width;
  final int height;

  VideoFrame(int[] argb, int width, int height) {
    this.argb = argb;
    this.width = width;
    this.height = height;
  }
}
