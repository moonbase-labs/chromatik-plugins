package laserphile.chromatik.touch;

/** One detected touch-like blob, with both Cartesian and polar coordinates. */
record TouchBlob(
  int segment,
  float xPixels,
  float yPixels,
  float normalizedX,
  float normalizedY,
  float angleDegrees,
  float distanceNormalized,
  float areaPixels) {
}
