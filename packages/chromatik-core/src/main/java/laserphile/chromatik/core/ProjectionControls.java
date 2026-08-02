package laserphile.chromatik.core;

import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.LXParameter;

/**
 * The controls for mapping a frame onto the model, shared by every pattern that projects one.
 *
 * None of these care where the pixels came from: {@link Projector} works on a texture and a point
 * cloud and never asks whether a frame was decoded from a file or grabbed off a display. So the
 * whole block is common to the Video and Screen Capture patterns, and lives here once rather than
 * being declared twice and drifting.
 *
 * This follows how LX shares a parameter block between two components: a holder that owns the
 * parameters plus a {@link LXParameter.Collection} of them, which the consumer registers in one
 * call with {@code addParameters(...)}. LX's own {@code SparklePattern.Engine} is reused by
 * {@code SparkleEffect} exactly this way.
 *
 * Each pattern owns its own instance. A parameter belongs to a single component, so a shared
 * instance would fail loudly on the second pattern to register it.
 */
public final class ProjectionControls {

  public final CompoundParameter yaw =
    new CompoundParameter("Yaw", 0, -180, 180).setDescription("Rotation about the vertical axis");
  public final CompoundParameter pitch =
    new CompoundParameter("Pitch", 0, -180, 180).setDescription("Rotation about the horizontal axis");
  public final CompoundParameter roll =
    new CompoundParameter("Roll", 0, -180, 180).setDescription("Rotation about the view axis");

  public final CompoundParameter translateX =
    new CompoundParameter("TransX", 0, -1, 1).setDescription("Shift the image horizontally");
  public final CompoundParameter translateY =
    new CompoundParameter("TransY", 0, -1, 1).setDescription("Shift the image vertically");
  public final CompoundParameter translateZ =
    new CompoundParameter("TransZ", 0, -1, 1).setDescription("Shift the image in depth");

  public final CompoundParameter scale =
    new CompoundParameter("Scale", 1, 0.1, 10).setDescription("Zoom: larger values zoom in");
  /**
   * The two per-axis stretches, labelled with the axis first.
   *
   * A knob is 40 pixels wide and its label is clipped to fit, which is about six characters, so a
   * label of "StretchX" reaches the knob as "Stretc" and the pair becomes indistinguishable. The
   * axis letter leads so that whatever survives the clip still says which axis it is.
   */
  public final CompoundParameter stretchX =
    new CompoundParameter("XScale", 1, 0.1, 10).setDescription("Horizontal stretch, on top of Scale");
  public final CompoundParameter stretchY =
    new CompoundParameter("YScale", 1, 0.1, 10).setDescription("Vertical stretch, on top of Scale");

  public final CompoundParameter scrollX =
    new CompoundParameter("ScrollX", 0, -1, 1).setDescription("Horizontal scroll offset");
  public final CompoundParameter scrollY =
    new CompoundParameter("ScrollY", 0, -1, 1).setDescription("Vertical scroll offset");

  public final EnumParameter<ProjectionParams.WrapMode> wrapMode =
    new EnumParameter<ProjectionParams.WrapMode>("Wrap", ProjectionParams.WrapMode.CLAMP)
      .setDescription("How to sample outside the image bounds");
  public final EnumParameter<ProjectionParams.BackgroundMode> backgroundMode =
    new EnumParameter<ProjectionParams.BackgroundMode>("Background", ProjectionParams.BackgroundMode.BLACK)
      .setDescription("Colour for points outside the image (Clip mode)");
  public final EnumParameter<ProjectionParams.Interpolation> interpolation =
    new EnumParameter<ProjectionParams.Interpolation>("Interp", ProjectionParams.Interpolation.BILINEAR)
      .setDescription("Nearest is blocky; bilinear is smoother");
  /**
   * Calibration against the fixtures, and worth a knob because a rig that is lit differently from
   * one song to the next wants it moved by hand. It is shared because it is about the LEDs, not the
   * source: a video and a captured desktop want the same curve.
   */
  public final CompoundParameter gamma =
    new CompoundParameter("Gamma", 1, 1, 3)
      .setDescription("Pulls mid-tones down; around 2.2 undoes video's own brightness curve");
  /**
   * Master brightness. In neither collection below, because it is the one projection control every
   * pattern wants on its first knob, ahead of anything the pattern itself contributes. Each pattern
   * registers it directly, first.
   */
  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1).setDescription("Master brightness");

  /**
   * The seven worth a knob after {@link #level}, in descending order of how much they deserve one.
   *
   * A pattern registers this straight after its own knob-worthy controls. Where the eighth knob
   * falls inside it therefore depends on what else that pattern put in front: Screen Capture spends
   * only Level, so all seven are on knobs, whilst Video also spends Speed, which pushes XScale one
   * past the knob row and onto the panel below it, leaving Gamma as its eighth knob.
   *
   * Pitch is not here. Of the three rotations it is the one a projection least often sweeps, and
   * leaving it off frees the slot for Gamma, which every rig wants to reach for once its LEDs are
   * in front of it.
   */
  public final LXParameter.Collection knobParameters = new LXParameter.Collection();

  /** Everything else: still on the panel and still mappable by hand, just never on a knob. */
  public final LXParameter.Collection remainingParameters = new LXParameter.Collection();

  private final ProjectionParams params = new ProjectionParams();
  private final Projector projector = new Projector();

  public ProjectionControls() {
    // Two things ride on these two blocks. The insertion order is the order the controls appear in
    // the panel, because a collection keeps what it is given in order. And each key becomes the
    // parameter's saved path, so these strings are what a saved project stores: changing one
    // silently drops that control's saved value the next time the project is opened. Note that two
    // keys deliberately differ from their field names, and that a control keeps its key when it
    // moves between the two collections, so moving one reorders the panel without disturbing
    // anything already saved.
    //
    // They are two collections rather than one so that a pattern can put a control of its own at
    // the seam, which is where its knob row ends. Screen Capture puts Freeze there. Video puts
    // nothing there and simply registers both back to back.
    this.knobParameters.add("scale", this.scale);
    this.knobParameters.add("scrollX", this.scrollX);
    this.knobParameters.add("scrollY", this.scrollY);
    this.knobParameters.add("yaw", this.yaw);
    this.knobParameters.add("roll", this.roll);
    this.knobParameters.add("gamma", this.gamma);
    this.knobParameters.add("stretchX", this.stretchX);

    this.remainingParameters.add("stretchY", this.stretchY);
    this.remainingParameters.add("pitch", this.pitch);
    this.remainingParameters.add("translateX", this.translateX);
    this.remainingParameters.add("translateY", this.translateY);
    this.remainingParameters.add("translateZ", this.translateZ);
    this.remainingParameters.add("wrap", this.wrapMode);
    this.remainingParameters.add("background", this.backgroundMode);
    this.remainingParameters.add("interpolation", this.interpolation);
  }

  /**
   * Snapshot the controls and project one frame onto the model.
   *
   * Engine thread only. The snapshot is taken once per frame so the per-point loop inside
   * {@link Projector} never has to read a parameter or compute a trig function.
   */
  public void project(VideoFrame frame, LXModel model, int[] colors) {
    this.params.yaw = this.yaw.getValue();
    this.params.pitch = this.pitch.getValue();
    this.params.roll = this.roll.getValue();
    this.params.translateX = this.translateX.getValue();
    this.params.translateY = this.translateY.getValue();
    this.params.translateZ = this.translateZ.getValue();
    this.params.scale = this.scale.getValue();
    this.params.stretchX = this.stretchX.getValue();
    this.params.stretchY = this.stretchY.getValue();
    this.params.scrollX = this.scrollX.getValue();
    this.params.scrollY = this.scrollY.getValue();
    this.params.wrapMode = this.wrapMode.getEnum();
    this.params.backgroundMode = this.backgroundMode.getEnum();
    this.params.interpolation = this.interpolation.getEnum();
    this.params.level = this.level.getValue();
    this.params.gamma = this.gamma.getValue();
    this.params.recompute();

    this.projector.project(frame, this.params, model, colors);
  }
}
