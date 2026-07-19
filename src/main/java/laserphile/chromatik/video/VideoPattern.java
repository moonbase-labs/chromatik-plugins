package laserphile.chromatik.video;

import java.io.File;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Plays a video file onto the LX model with an ImagePattern-style projection.
 *
 * A background thread decodes frames; run() projects the latest frame onto every point using
 * the projection controls. M2 scope. Transport (play/pause/loop/speed/seek) is M3, so for now
 * playback auto-starts and loops.
 */
@LXCategory("Laserphile")
@LXComponent.Name("Video")
public class VideoPattern extends LXPattern {

  private final LX lx;

  public final StringParameter fileName =
    new StringParameter("File", "LaserphileVideo/steamed-hams.mp4")
      .setDescription("Video file: an absolute path, or a path relative to ~/Chromatik");

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
  public final CompoundParameter stretchX =
    new CompoundParameter("StretchX", 1, 0.1, 10).setDescription("Horizontal stretch");
  public final CompoundParameter stretchY =
    new CompoundParameter("StretchY", 1, 0.1, 10).setDescription("Vertical stretch");

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

  private final FramePipeline pipeline = new FramePipeline();
  private final Projector projector = new Projector();
  private final ProjectionParams params = new ProjectionParams();

  private volatile boolean active = false;

  public VideoPattern(LX lx) {
    super(lx);
    this.lx = lx;

    addParameter("file", this.fileName);
    addParameter("yaw", this.yaw);
    addParameter("pitch", this.pitch);
    addParameter("roll", this.roll);
    addParameter("translateX", this.translateX);
    addParameter("translateY", this.translateY);
    addParameter("translateZ", this.translateZ);
    addParameter("scale", this.scale);
    addParameter("stretchX", this.stretchX);
    addParameter("stretchY", this.stretchY);
    addParameter("scrollX", this.scrollX);
    addParameter("scrollY", this.scrollY);
    addParameter("wrap", this.wrapMode);
    addParameter("background", this.backgroundMode);
    addParameter("interpolation", this.interpolation);

    this.fileName.addListener(parameter -> {
      if (this.active) {
        restart();
      }
    });
  }

  private void restart() {
    this.pipeline.stop();

    final String resolved = resolvePath(this.fileName.getString());
    if (resolved != null) {
      this.pipeline.start(resolved);
    }
  }

  /** Absolute paths are used as-is; relative paths resolve under ~/Chromatik for portability. */
  private String resolvePath(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }

    File file = new File(raw);
    if (!file.isAbsolute()) {
      file = this.lx.getMediaFile(raw);
    }

    return file.getAbsolutePath();
  }

  @Override
  protected void onActive() {
    this.active = true;
    restart();
  }

  @Override
  protected void onInactive() {
    this.active = false;
    this.pipeline.stop();
  }

  @Override
  public void dispose() {
    this.pipeline.stop();
    super.dispose();
  }

  @Override
  protected void run(double deltaMs) {
    final VideoFrame frame = this.pipeline.latest();

    if (frame == null) {
      setColors(LXColor.hsb(0, 0, 0)); // black until the first frame is decoded
      return;
    }

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
    this.params.recompute();

    this.projector.project(frame, this.params, this.model, this.colors);
  }
}
