package laserphile.chromatik.video;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import heronarts.glx.GLX;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
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
 *
 * For the live desktop rather than a file, see {@link ScreenCapturePattern}, which shares the
 * projection controls but has no timeline and so none of the transport.
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

  // Empty until the user picks something. A default pointing at a specific clip would be a
  // file-not-found for everyone who does not happen to have that clip.
  public final StringParameter fileName =
    new StringParameter("File", "")
      .setDescription("Video file: an absolute path, or a path relative to ~/Chromatik");
  public final TriggerParameter browse =
    new TriggerParameter("Browse").setDescription("Pick a video file");
  public final TriggerParameter reload =
    new TriggerParameter("Reload").setDescription("Re-open the current source");

  public final DiscreteParameter workingResolution =
    new DiscreteParameter("Res", WorkingResolution.OPTIONS, WorkingResolution.AUTO_OPTION)
      .setDescription("Longest edge to decode frames at; Auto follows the model's point count");

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

  /** Orientation, scale, wrapping, sampling and brightness, shared with the other patterns. */
  public final ProjectionControls projection = new ProjectionControls();

  private final FramePipeline pipeline = new FramePipeline();
  private final PlaybackClock clock = new PlaybackClock();

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

    // The panel draws the parameters in the order they are added here, filling a row at a time,
    // and the remote-control list below repeats that order. So the panel reads left to right in
    // the same order a control surface sees it, starting with the eight knobs.
    addParameter("level", this.projection.level);
    addParameter("speed", this.speed);
    // Speed has taken a knob, so the last of these lands one past the knob row.
    addParameters(this.projection.knobParameters);

    addParameters(this.projection.remainingParameters);

    addParameter("position", this.position);
    addParameter("play", this.play);
    addParameter("loop", this.loop);
    addParameter("restart", this.restart);

    // Everything below is held back from the surface, so it goes last. Anything surface-less added
    // earlier would offset every control after it and break the panel's match with the knobs.
    addParameter("workingResolution", this.workingResolution);
    addParameter("browse", this.browse);
    addParameter("reload", this.reload);

    // No control of its own in the panel, which only draws numbers, switches and dropdowns. It is
    // registered so the chosen path is saved with the project, and it is what Browse writes to.
    addParameter("file", this.fileName);

    setRemoteControls(
      // A MIDI surface binds its eight device knobs to the first eight entries here, so all eight
      // are continuous controls. An APC40 cannot page past its eighth knob, so a button or a
      // trigger in this range costs a knob outright.
      this.projection.level,
      this.speed,
      this.projection.scale,
      this.projection.scrollX,
      this.projection.scrollY,
      this.projection.yaw,
      this.projection.pitch,
      this.projection.roll,
      // Past the eighth knob. Still mappable by hand, just not picked up by a surface. This order
      // matches the panel, so anything inserted here has to be inserted there too.
      this.projection.stretchX,
      this.projection.stretchY,
      this.projection.translateX,
      this.projection.translateY,
      this.projection.translateZ,
      this.projection.wrapMode,
      this.projection.backgroundMode,
      this.projection.interpolation,
      this.position,
      this.play,
      this.loop,
      this.restart);
    // Browse and Reload stay out of the list: both open or re-read a file from disk, which is not
    // something to hand to a control surface. Res stays out for the same reason, more so: changing
    // it tears down the current source and opens another. All three are the tail of the panel, so
    // every control ahead of them lines up with its position on a surface.

    this.fileName.addListener(parameter -> this.openRequested = true);
    this.workingResolution.addListener(parameter -> this.openRequested = true);
    this.browse.onTrigger(this::showFileChooser);
    this.reload.onTrigger(() -> this.openRequested = true);
    this.restart.onTrigger(() -> this.restartRequested = true);

    this.position.addListener(parameter -> {
      if (!this.updatingPosition) {
        this.positionEdited = true;
      }
    });
  }

  private void openCurrentSource() {
    this.pipeline.stop();
    this.clock.reset();

    final FrameSource source = fileSource();

    if (source == null) {
      return; // nothing to open, and fileSource() has already said why
    }

    this.pipeline.start(
      source, WorkingResolution.edgeFor(this.workingResolution.getValuei(), this.model.size));
  }

  /** The chosen file as a source, or null when there is nothing openable to point at. */
  private FrameSource fileSource() {
    final String resolved = resolvePath(this.fileName.getString());

    if (resolved == null) {
      LX.log("[LaserphileVideo] no video selected: use Browse, or type a path into File");
      return null;
    }

    // Checked here so a mistyped path, or a project opened on a machine without the clip, says so
    // plainly. Left to FFmpeg it arrives as an error about an I/O layer, naming nothing useful.
    if (!new File(resolved).isFile()) {
      LX.log(String.format("[LaserphileVideo] video file not found: %s", resolved));
      return null;
    }

    return new FileVideoSource(resolved);
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

  /**
   * A new model means a new point count, and on Auto that is what sets the decode size, so the
   * source has to be opened again to take effect. Any fixed size is unaffected.
   */
  @Override
  protected void onModelChanged(LXModel model) {
    super.onModelChanged(model);

    if (this.workingResolution.getValuei() == WorkingResolution.AUTO_OPTION) {
      this.openRequested = true;
    }
  }

  @Override
  protected void onActive() {
    this.openRequested = false;
    openCurrentSource();
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
      // Nothing to draw yet, or nothing ever: a source still opening, one that failed to open, or
      // a file that was never chosen. The Background control says what should show through.
      setColors(ProjectionParams.backgroundColor(this.projection.backgroundMode.getEnum()));
      return;
    }

    updatePlayheadReadout(frame);

    this.projection.project(frame, this.model, this.colors);
  }

  /**
   * Fold the transport controls into the clock and the decode thread, then advance the playhead.
   * Every mutation of the clock and the pipeline funnels through here so it all happens on the
   * engine thread, which is why the parameter listeners only raise flags.
   */
  private void serviceTransport(double deltaMs) {
    if (this.openRequested) {
      this.openRequested = false;
      openCurrentSource();
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
