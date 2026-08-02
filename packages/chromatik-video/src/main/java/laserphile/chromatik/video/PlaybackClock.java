package laserphile.chromatik.video;

/**
 * The playhead, owned by the engine thread. Pure state: no I/O, no locking, no threading.
 *
 * Time is tracked as a continuous stream time that keeps counting past the end of the media
 * rather than wrapping back to zero at the loop point. Looping happens on the decode thread so
 * it can be gapless, and that thread stamps looped frames onto this same continuous timeline,
 * so both sides stay in step with no round-trip. A seek is the one discontinuity: it jumps the
 * timeline to the requested media time, and every frame decoded from then on is stamped from
 * there.
 */
final class PlaybackClock {

  private static final double NO_PENDING_SEEK = -1;

  private double streamTimeMs = 0;
  private double pendingSeekMs = NO_PENDING_SEEK;
  private boolean playing = true;
  private double speed = 1;

  /** Advance the playhead by one engine frame. Speed scales media time, never decode rate. */
  void tick(double deltaMs) {
    if (this.playing) {
      this.streamTimeMs += deltaMs * this.speed;
    }
  }

  void setPlaying(boolean playing) {
    this.playing = playing;
  }

  void setSpeed(double speed) {
    this.speed = speed;
  }

  /** Scrubbing overwrites the target rather than queueing, so only the newest seek is served. */
  void requestSeek(double mediaTimeMs) {
    this.pendingSeekMs = Math.max(0, mediaTimeMs);
  }

  boolean hasPendingSeek() {
    return this.pendingSeekMs != NO_PENDING_SEEK;
  }

  /** Take the pending target and jump the timeline to it. */
  long takePendingSeek() {
    final long target = Math.round(this.pendingSeekMs);

    this.pendingSeekMs = NO_PENDING_SEEK;
    this.streamTimeMs = target;

    return target;
  }

  /** Back to a fresh timeline, used when a different file is opened. */
  void reset() {
    this.streamTimeMs = 0;
    this.pendingSeekMs = NO_PENDING_SEEK;
  }

  long streamTimeMs() {
    return Math.round(this.streamTimeMs);
  }
}
