package laserphile.chromatik.video;

/**
 * Where the pattern gets its frames from.
 *
 * A file has a timeline, so the transport controls drive it. A screen has no timeline: frames
 * arrive as they happen and the transport controls have nothing to move.
 */
// Public because EnumParameter reflects on values() from another package; a package-private enum
// compiles fine and then throws IllegalAccessException when the pattern is instantiated.
public enum SourceType {

  FILE("File"),
  SCREEN("Screen");

  private final String label;

  SourceType(String label) {
    this.label = label;
  }

  /** The auto-generated panel shows this, so it is a label rather than the enum constant name. */
  @Override
  public String toString() {
    return this.label;
  }
}
