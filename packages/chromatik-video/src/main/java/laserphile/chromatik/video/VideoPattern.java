package laserphile.chromatik.video;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import heronarts.glx.GLX;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.EnumParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import heronarts.lx.pattern.LXPattern;

/**
 * Plays a video file onto the LX model with an ImagePattern-style projection.
 *
 * A background thread decodes frames into a buffer; run() advances the playhead, picks the
 * frame due now, and projects it onto every point. Transport (play/pause, loop, speed, seek,
 * restart) drives the playhead on this thread and reaches the decoder through the pipeline's
 * mailbox, so nothing here blocks on decode or I/O.
 */
@LXCategory("Laserphile")
@LXComponent.Name("Video")
public class VideoPattern extends LXPattern {

  /**
   * How long the playhead readout holds still after the user edits it. Without the pause, the
   * frame-by-frame update would fight a drag in progress and yank the slider back.
   */
  private static final double QUARTER_SECOND_IN_MS = 250;

  /** Offered by the file chooser. FFmpeg reads far more; these are the containers worth listing. */
  private static final String[] VIDEO_EXTENSIONS = { "mp4", "mov", "m4v", "avi", "mkv", "webm" };

  /**
   * Folder the last browse landed in. Shared by every Video pattern and kept for the run of the
   * app, so a freshly added pattern opens the chooser where the previous one finished instead of
   * back at the media folder. Written from the dialog callback, read when the next dialog opens.
   */
  private static volatile String lastBrowsedFolder = null;

  private final LX lx;

  public final StringParameter fileName =
    new StringParameter("File", "LaserphileVideo/steamed-hams.mp4")
      .setDescription("Video file: an absolute path, or a path relative to ~/Chromatik");
  public final TriggerParameter browse =
    new TriggerParameter("Browse").setDescription("Pick a video file");
  public final TriggerParameter reload =
    new TriggerParameter("Reload").setDescription("Re-open the video file");

  public final BooleanParameter play =
    new BooleanParameter("Play", true).setDescription("Run the playhead");
  public final BooleanParameter loop =
    new BooleanParameter("Loop", true).setDescription("Start again on reaching the end");
  public final CompoundParameter speed =
    new CompoundParameter("Speed", 1, 0.1, 4).setExponent(2)
      .setDescription("Playback rate: 1 is normal speed");
  public final CompoundParameter position =
    new CompoundParameter("Position", 0, 0, 1)
      .setDescription("Playhead through the video: follows playback, and seeks when dragged");
  public final TriggerParameter restart =
    new TriggerParameter("Restart").setDescription("Jump back to the start and play");

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
  public final CompoundParameter level =
    new CompoundParameter("Level", 1, 0, 1).setDescription("Master brightness");

  private final FramePipeline pipeline = new FramePipeline();
  private final PlaybackClock clock = new PlaybackClock();
  private final Projector projector = new Projector();
  private final ProjectionParams params = new ProjectionParams();

  // Parameter listeners can fire on the UI thread, so they only raise a flag; run() acts on it.
  private volatile boolean openRequested = false;
  private volatile boolean restartRequested = false;
  private volatile boolean positionEdited = false;

  // Set while run() writes the playhead readout, so the write is not mistaken for a user edit.
  private boolean updatingPosition = false;
  private double msSinceUserPositionEdit = QUARTER_SECOND_IN_MS;

  public VideoPattern(LX lx) {
    super(lx);
    this.lx = lx;

    addParameter("file", this.fileName);
    addParameter("browse", this.browse);
    addParameter("reload", this.reload);
    addParameter("play", this.play);
    addParameter("loop", this.loop);
    addParameter("speed", this.speed);
    addParameter("position", this.position);
    addParameter("restart", this.restart);
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
    addParameter("level", this.level);

    this.fileName.addListener(parameter -> this.openRequested = true);
    this.browse.onTrigger(this::showFileChooser);
    this.reload.onTrigger(() -> this.openRequested = true);
    this.restart.onTrigger(() -> this.restartRequested = true);

    this.position.addListener(parameter -> {
      if (!this.updatingPosition) {
        this.positionEdited = true;
      }
    });
  }

  private void openCurrentFile() {
    this.pipeline.stop();
    this.clock.reset();

    final String resolved = resolvePath(this.fileName.getString());
    if (resolved != null) {
      this.pipeline.start(resolved);
    }
  }

  /**
   * Open the desktop file chooser.
   *
   * The dialog belongs to GLX, the windowed build of LX, and GLX extends LX, so the same object
   * the pattern was handed at construction is the one that can show it. Under a plain headless
   * LX there is no window and no dialog, hence the check.
   */
  private void showFileChooser() {
    if (!(this.lx instanceof GLX glx)) {
      LX.log("[LaserphileVideo] the file chooser needs the Chromatik desktop app");
      return;
    }

    glx.showOpenFileDialog(
      "Open Video",
      "Video File",
      VIDEO_EXTENSIONS,
      chooserStartPath(),
      this::onFileChosen);
  }

  /**
   * Where the chooser opens: the folder holding the current video, else the folder the last
   * browse landed in, else the Chromatik media folder.
   *
   * The dialog reads whatever follows the final separator as a file name and opens the folder
   * above it, so a folder has to be handed over with its trailing separator kept.
   */
  private String chooserStartPath() {
    final File currentFolder = parentFolderOf(resolvePath(this.fileName.getString()));

    if (currentFolder != null) {
      return asFolderPath(currentFolder.getAbsolutePath());
    }

    if (lastBrowsedFolder != null) {
      return asFolderPath(lastBrowsedFolder);
    }

    return asFolderPath(this.lx.getMediaPath());
  }

  /** The folder holding the given file, or null when that folder is not on disk. */
  private static File parentFolderOf(String path) {
    if (path == null) {
      return null;
    }

    final File parent = new File(path).getParentFile();

    return (parent != null && parent.isDirectory()) ? parent : null;
  }

  private static String asFolderPath(String folder) {
    if (folder == null || folder.isBlank()) {
      return folder;
    }

    return folder.endsWith(File.separator) ? folder : folder.concat(File.separator);
  }

  private void onFileChosen(String chosenPath) {
    if (chosenPath == null || chosenPath.isBlank()) {
      return; // the user cancelled
    }

    lastBrowsedFolder = new File(chosenPath).getParent();

    this.fileName.setValue(relativizeToMediaFolder(chosenPath));
  }

  /**
   * Store a path under ~/Chromatik as a relative one, so a project shared with someone else
   * still finds the video next to their own Chromatik folder. Anything outside stays absolute,
   * because there is nothing portable to say about it.
   */
  private String relativizeToMediaFolder(String chosenPath) {
    final String mediaPath = this.lx.getMediaPath();

    if (mediaPath == null || mediaPath.isBlank()) {
      return chosenPath;
    }

    final Path mediaFolder = Paths.get(mediaPath).toAbsolutePath().normalize();
    final Path chosen = Paths.get(chosenPath).toAbsolutePath().normalize();

    if (!chosen.startsWith(mediaFolder)) {
      return chosenPath;
    }

    return mediaFolder.relativize(chosen).toString();
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
    this.openRequested = false;
    openCurrentFile();
  }

  @Override
  protected void onInactive() {
    this.pipeline.stop();
  }

  @Override
  public void dispose() {
    this.pipeline.stop();
    super.dispose();
  }

  @Override
  protected void run(double deltaMs) {
    serviceTransport(deltaMs);

    final VideoFrame frame = this.pipeline.frameFor(this.clock.streamTimeMs());

    if (frame == null) {
      setColors(LXColor.hsb(0, 0, 0)); // black until the first frame is decoded
      return;
    }

    updatePlayheadReadout(frame);

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
    this.params.recompute();

    this.projector.project(frame, this.params, this.model, this.colors);
  }

  /**
   * Fold the transport controls into the clock and the decode thread, then advance the playhead.
   * Every mutation of the clock and the pipeline funnels through here so it all happens on the
   * engine thread, which is why the parameter listeners only raise flags.
   */
  private void serviceTransport(double deltaMs) {
    if (this.openRequested) {
      this.openRequested = false;
      openCurrentFile();
    }

    this.pipeline.setLooping(this.loop.isOn());
    this.clock.setSpeed(this.speed.getValue());
    this.clock.setPlaying(this.play.isOn());

    if (this.restartRequested) {
      this.restartRequested = false;
      this.clock.requestSeek(0);
      this.play.setValue(true);
    }

    if (this.positionEdited) {
      this.positionEdited = false;
      this.msSinceUserPositionEdit = 0;

      final long durationMs = this.pipeline.durationMs();
      if (durationMs > 0) {
        this.clock.requestSeek(this.position.getValue() * durationMs);
      }
    } else if (this.msSinceUserPositionEdit < QUARTER_SECOND_IN_MS) {
      this.msSinceUserPositionEdit += deltaMs;
    }

    if (this.clock.hasPendingSeek()) {
      this.pipeline.requestSeek(this.clock.takePendingSeek());
    }

    this.clock.tick(deltaMs);

    if (this.pipeline.isDrained()) {
      this.play.setValue(false); // ran off the end with looping off; Restart picks it up again
    }
  }

  /** Push the playhead back to the slider, unless the user is the one moving it. */
  private void updatePlayheadReadout(VideoFrame frame) {
    final long durationMs = this.pipeline.durationMs();

    if (durationMs <= 0 || this.msSinceUserPositionEdit < QUARTER_SECOND_IN_MS) {
      return;
    }

    this.updatingPosition = true;
    this.position.setValue(Math.min(1.0, frame.mediaTimeMs / (double) durationMs));
    this.updatingPosition = false;
  }
}
