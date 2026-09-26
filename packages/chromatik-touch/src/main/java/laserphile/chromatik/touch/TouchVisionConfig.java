package laserphile.chromatik.touch;

import java.util.concurrent.atomic.AtomicBoolean;

/** Mutable config shared between the UI thread and the capture thread. */
final class TouchVisionConfig {

  volatile boolean circularMaskEnabled = true;
  volatile boolean invertDifference = true;
  volatile boolean overlayBlobs = true;

  volatile double maskRadiusNormalized = 0.95;
  volatile double currentGain = 0.85;

  volatile int threshold = 72;
  volatile int minBlobArea = 150;
  volatile int segmentCount = 10;

  volatile boolean oscEnabled = false;
  volatile String oscHost = "127.0.0.1";
  volatile int oscPort = 7000;
  volatile String oscPrefix = "/touch";

  private final AtomicBoolean calibrationRequested = new AtomicBoolean(true);

  void requestCalibration() {
    this.calibrationRequested.set(true);
  }

  boolean takeCalibrationRequest() {
    return this.calibrationRequested.getAndSet(false);
  }
}
