package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import heronarts.lx.LX;
import heronarts.lx.LXComponent;
import heronarts.lx.LXPath;
import heronarts.lx.command.LXCommand;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.mixer.LXAbstractChannel;
import heronarts.lx.mixer.LXChannel;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.pattern.LXPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tools that change something.
 *
 * <p>Every one of them follows the same three steps, for a reason worth stating once here rather
 * than at each call site. {@code LXCommandEngine.perform} catches whatever the command throws,
 * pushes an error dialog, <em>clears the undo stack</em>, and returns normally. From the caller's
 * side a failed command is indistinguishable from a successful one. So each tool:
 *
 * <ol>
 *   <li><b>validates first</b>, against the live registry and the live mixer, and refuses before
 *       {@code perform} is ever reached. This catches nearly everything that would otherwise be a
 *       silent no-op;
 *   <li><b>performs</b> through {@code LXCommand}, so the change lands in Chromatik's own undo
 *       history and Cmd-Z reverses it exactly as if it had been clicked;
 *   <li><b>reads the result back</b>, in the same engine task, and reports the actual value.
 * </ol>
 *
 * <p>Step three is what makes {@code lx_set} honest about clamping, which a parameter does
 * silently and an agent otherwise has no way to notice.
 */
final class WriteTools {

  static void register(ToolRegistry registry, LX lx, EngineBridge bridge, ParameterCatalog catalog) {
    registry.add(new SetTool(lx, bridge, catalog));
    registry.add(new AddTool(lx, bridge, catalog));
    registry.add(new UndoTool(lx, bridge));
  }

  // ---- lx_set -------------------------------------------------------------

  /** Set any number of parameters in one call. */
  private record SetTool(LX lx, EngineBridge bridge, ParameterCatalog catalog) implements Tool {

    @Override
    public String name() {
      return "lx_set";
    }

    @Override
    public String title() {
      return "Set parameters";
    }

    @Override
    public String description() {
      return "Sets one or more parameters by canonical path, in a single undoable call. Batch "
          + "related changes rather than calling once per parameter. Set enums by option name, not "
          + "index. Reports the value that actually landed, which differs from the one requested "
          + "when a parameter clamps to its range. Call lx_docs first if unsure of a path key.";
    }

    @Override
    public JsonObject inputSchema() {
      final JsonObject entry = new JsonObject();
      entry.addProperty("type", "object");

      final JsonObject properties = new JsonObject();
      properties.add("path", Schema.string("Canonical parameter path, e.g. "
          + "/lx/mixer/channel/1/pattern/1/scale. A path relative to \"under\" also works."));
      properties.add("value", new JsonObject());
      properties.get("value").getAsJsonObject().addProperty("description",
          "Number, boolean, enum option name, or string, matching the parameter's type.");
      entry.add("properties", properties);

      final JsonArray required = new JsonArray();
      required.add("path");
      required.add("value");
      entry.add("required", required);

      final JsonObject sets = new JsonObject();
      sets.addProperty("type", "array");
      sets.addProperty("description", "The parameters to set.");
      sets.add("items", entry);

      return Schema.required(Schema.object(
          "sets", sets,
          "under", Schema.string("Optional path prefix applied to every entry, so paths can be "
              + "given as bare keys such as \"scale\"."),
          "dryRun", Schema.bool("Resolve and report without changing anything.", false)),
          "sets");
    }

    @Override
    public boolean readOnly() {
      return false;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      if (!arguments.has("sets") || !arguments.get("sets").isJsonArray()) {
        throw new McpToolException("\"sets\" must be an array of {path, value} objects");
      }

      final JsonArray sets = arguments.getAsJsonArray("sets");

      if (sets.isEmpty()) {
        throw new McpToolException("\"sets\" was empty, so there was nothing to do");
      }

      final String under = Args.optionalString(arguments, "under", null);
      final boolean dryRun = Args.optionalBoolean(arguments, "dryRun", false);

      return this.bridge.call("lx_set", () -> {
        final JsonArray results = new JsonArray();
        final List<String> descriptions = new ArrayList<>();
        int applied = 0;
        int failed = 0;

        for (JsonElement element : sets) {
          if (!element.isJsonObject()) {
            throw new ToolRefusal("every entry in \"sets\" must be an object with path and value");
          }

          final JsonObject entry = element.getAsJsonObject();
          final JsonObject outcome = applyOne(entry, under, dryRun, descriptions);
          results.add(outcome);

          if (outcome.has("error")) {
            failed++;
          } else {
            applied++;
          }
        }

        final JsonObject result = new JsonObject();
        result.addProperty(dryRun ? "wouldApply" : "applied", applied);
        result.addProperty("failed", failed);
        result.add("results", results);

        if (!dryRun && !descriptions.isEmpty()) {
          // Naming the undo entries tells the agent the shape of what it would be reversing.
          result.addProperty("undo", String.join("; ", descriptions));
        }

        return result;
      });
    }

    private JsonObject applyOne(JsonObject entry, String under, boolean dryRun, List<String> descriptions) {
      final JsonObject outcome = new JsonObject();

      final String rawPath = entry.has("path") && entry.get("path").isJsonPrimitive()
          ? entry.get("path").getAsString()
          : null;

      if (rawPath == null || rawPath.isBlank()) {
        outcome.addProperty("path", "");
        outcome.addProperty("error", "missing \"path\"");
        return outcome;
      }

      final String path = join(under, rawPath);
      outcome.addProperty("path", path);

      final LXParameter parameter = LXPath.getParameter(this.lx, path);

      if (parameter == null) {
        outcome.addProperty("error", "no parameter at this path");

        final JsonArray suggestions = suggest(path);

        // A wrong path is the single most common failure, and it is almost always a near miss:
        // a field name instead of a key, or a label. Suggesting neighbours turns a three-call
        // recovery into one.
        if (!suggestions.isEmpty()) {
          outcome.add("didYouMean", suggestions);
        }

        return outcome;
      }

      if (!entry.has("value")) {
        outcome.addProperty("error", "missing \"value\"");
        return outcome;
      }

      final JsonElement requested = entry.get("value");
      outcome.add("was", this.catalog.valueOf(parameter));

      try {
        final LXCommand command = commandFor(parameter, requested);

        if (dryRun) {
          outcome.add("would", requested);
          return outcome;
        }

        this.lx.command.perform(command);
        descriptions.add(command.getDescription());

      } catch (ToolRefusal refused) {
        outcome.addProperty("error", refused.getMessage());
        return outcome;
      }

      // Read back rather than trust. perform() swallows failures, and a parameter clamps quietly.
      final JsonElement actual = this.catalog.valueOf(parameter);
      outcome.add("now", actual);

      if (!actual.equals(requested) && !looselyEqual(actual, requested)) {
        outcome.addProperty("clamped", true);
      }

      return outcome;
    }

    /** The right command for this parameter's type, with the value coerced to match. */
    private LXCommand commandFor(LXParameter parameter, JsonElement requested) {
      if (parameter instanceof BooleanParameter flag) {
        return new LXCommand.Parameter.SetNormalized(flag, asBoolean(requested, parameter));
      }

      if (parameter instanceof StringParameter text) {
        return new LXCommand.Parameter.SetString(text, requested.getAsString());
      }

      if (parameter instanceof DiscreteParameter discrete) {
        return new LXCommand.Parameter.SetValue(discrete, discreteValue(discrete, requested));
      }

      final double value = asNumber(requested, parameter);

      if (parameter instanceof BoundedParameter bounded) {
        // Deliberately not setNormalized. The agent is told the parameter's real range by lx_docs,
        // so it speaks in real units, and an exponent-2 control would otherwise land somewhere it
        // did not ask for.
        return new LXCommand.Parameter.SetValue(bounded, value);
      }

      return new LXCommand.Parameter.SetValue(parameter, value);
    }

    /**
     * A discrete parameter's target index.
     *
     * <p>By option name whenever the parameter has options, because indices shift when a list
     * changes and a number is exactly the kind of thing that keeps working until it silently does
     * not. A number is still accepted, since a plain integer parameter has no names.
     */
    private int discreteValue(DiscreteParameter discrete, JsonElement requested) {
      final String[] options = discrete.getOptions();

      if (options != null && requested.isJsonPrimitive() && requested.getAsJsonPrimitive().isString()) {
        final String wanted = requested.getAsString();

        for (int index = 0; index < options.length; index++) {
          if (options[index].equalsIgnoreCase(wanted)) {
            return discrete.getMinValue() + index;
          }
        }

        throw new ToolRefusal("\"" + wanted + "\" is not one of " + String.join(", ", options));
      }

      try {
        return requested.getAsInt();
      } catch (RuntimeException notANumber) {
        throw new ToolRefusal("expected " + (options != null
            ? "one of " + String.join(", ", options)
            : "a whole number between " + discrete.getMinValue() + " and " + discrete.getMaxValue()));
      }
    }

    private boolean asBoolean(JsonElement requested, LXParameter parameter) {
      if (requested.isJsonPrimitive() && requested.getAsJsonPrimitive().isBoolean()) {
        return requested.getAsBoolean();
      }

      if (requested.isJsonPrimitive() && requested.getAsJsonPrimitive().isNumber()) {
        return requested.getAsDouble() != 0;
      }

      final String text = requested.getAsString();

      if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
        return Boolean.parseBoolean(text);
      }

      throw new ToolRefusal(parameter.getLabel() + " is a boolean, so it takes true or false");
    }

    private double asNumber(JsonElement requested, LXParameter parameter) {
      try {
        return requested.getAsDouble();
      } catch (RuntimeException notANumber) {
        throw new ToolRefusal(parameter.getLabel() + " is a number, but got " + requested);
      }
    }

    /**
     * Whether the value that landed is the value that was asked for, allowing for how it travelled.
     *
     * <p>Two things stop a plain equality check from being right, and both would otherwise report
     * a perfectly successful set as clamped, which tells the agent to go and fix something that is
     * not broken. Numbers come back rounded for readability and through a parameter's own
     * precision. Enums are matched by name case-insensitively on the way in and reported in their
     * declared case on the way out, so asking for "mirror" correctly yields "MIRROR".
     */
    private boolean looselyEqual(JsonElement actual, JsonElement requested) {
      if (!actual.isJsonPrimitive() || !requested.isJsonPrimitive()) {
        return false;
      }

      final var landed = actual.getAsJsonPrimitive();
      final var asked = requested.getAsJsonPrimitive();

      if (landed.isNumber() && asked.isNumber()) {
        return Math.abs(landed.getAsDouble() - asked.getAsDouble()) < 1e-6;
      }

      if (landed.isString() && asked.isString()) {
        return landed.getAsString().equalsIgnoreCase(asked.getAsString());
      }

      if (landed.isBoolean() && asked.isBoolean()) {
        return landed.getAsBoolean() == asked.getAsBoolean();
      }

      return false;
    }

    /** Sibling parameter keys that look like what was asked for. */
    private JsonArray suggest(String path) {
      final int lastSlash = path.lastIndexOf('/');
      final JsonArray suggestions = new JsonArray();

      if (lastSlash <= 0) {
        return suggestions;
      }

      final LXComponent owner = LXPath.getComponent(this.lx, path.substring(0, lastSlash));

      if (owner == null) {
        return suggestions;
      }

      final String wanted = path.substring(lastSlash + 1).toLowerCase(Locale.ROOT);

      for (LXParameter candidate : owner.getParameters()) {
        final String key = candidate.getPath();

        if (isNearMiss(wanted, key, candidate.getLabel()) && suggestions.size() < 5) {
          suggestions.add(key);
        }
      }

      return suggestions;
    }

    /**
     * Whether a key is close enough to what was asked for to be worth offering.
     *
     * <p>Three ways to be close, each for a mistake seen in practice. Prefix containment catches a
     * Java field name reached for instead of the path key, which is how {@code wrapMode} finds
     * {@code wrap}. An exact label match catches an agent working from a panel screenshot or a
     * README table rather than from lx_docs. A shared prefix catches the same-family-wrong-suffix
     * guess, which the first two both miss: {@code scrollSpeed} shares six characters with
     * {@code scrollX} and {@code scrollY} but neither contains the other.
     *
     * <p>Four characters is the threshold. Shorter and unrelated controls start matching on a
     * common English stem, which buries the real candidate in noise.
     */
    private boolean isNearMiss(String wanted, String key, String label) {
      final String candidate = key.toLowerCase(Locale.ROOT);

      if (candidate.startsWith(wanted) || wanted.startsWith(candidate)) {
        return true;
      }

      if (label != null && label.toLowerCase(Locale.ROOT).equals(wanted)) {
        return true;
      }

      return sharedPrefixLength(wanted, candidate) >= 4;
    }

    private int sharedPrefixLength(String left, String right) {
      final int shortest = Math.min(left.length(), right.length());
      int shared = 0;

      while (shared < shortest && left.charAt(shared) == right.charAt(shared)) {
        shared++;
      }

      return shared;
    }

    private String join(String under, String path) {
      if (under == null || under.isBlank() || path.startsWith("/")) {
        return normalize(path);
      }

      return normalize(under.endsWith("/") ? under + path : under + "/" + path);
    }
  }

  // ---- lx_add -------------------------------------------------------------

  /** Create a channel, a pattern or an effect. */
  private record AddTool(LX lx, EngineBridge bridge, ParameterCatalog catalog) implements Tool {

    @Override
    public String name() {
      return "lx_add";
    }

    @Override
    public String title() {
      return "Add a component";
    }

    @Override
    public String description() {
      return "Adds a channel, pattern or effect, and returns the canonical path of what it made so "
          + "the next call can address it. Get the class name from lx_catalog; do not guess it. "
          + "Omit \"parent\" when adding a channel. Undoable.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.required(Schema.object(
          "kind", Schema.choice("What to create.", "channel", "pattern", "effect"),
          "class", Schema.string("Fully-qualified class name from lx_catalog. For a channel this "
              + "is the pattern it starts with, and may be omitted for an empty channel."),
          "parent", Schema.string("Channel path for a pattern or effect, e.g. /lx/mixer/channel/1. "
              + "Use /lx/mixer/master for a master-bus effect.")),
          "kind");
    }

    @Override
    public boolean readOnly() {
      return false;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      final String kind = Args.requireString(arguments, "kind");
      final String className = Args.optionalString(arguments, "class", null);
      final String parentPath = Args.optionalString(arguments, "parent", null);

      return this.bridge.call("lx_add", () -> switch (kind) {
        case "channel" -> addChannel(className);
        case "pattern" -> addPattern(className, parentPath);
        case "effect" -> addEffect(className, parentPath);
        default -> throw new ToolRefusal("\"kind\" must be channel, pattern or effect");
      });
    }

    private JsonObject addChannel(String className) {
      final int before = this.lx.engine.mixer.channels.size();

      final LXCommand command = className == null
          ? new LXCommand.Mixer.AddChannel()
          : new LXCommand.Mixer.AddChannel(patternClass(className));

      this.lx.command.perform(command);

      // perform() swallows failures, so the only trustworthy signal is that the mixer grew.
      if (this.lx.engine.mixer.channels.size() != before + 1) {
        throw new ToolRefusal("the channel was not added; check the Chromatik log for why");
      }

      final LXAbstractChannel added = this.lx.engine.mixer.channels.get(before);

      final JsonObject result = new JsonObject();
      result.addProperty("created", added.getCanonicalPath());
      result.addProperty("index", before + 1);
      result.addProperty("label", added.getLabel());

      if (added instanceof LXChannel channel && !channel.patterns.isEmpty()) {
        final LXPattern pattern = channel.patterns.get(0);
        result.addProperty("pattern", pattern.getCanonicalPath());
        result.add("params", curatedValues(pattern));
        addWarnings(result, pattern);
      }

      result.addProperty("undo", command.getDescription());

      return result;
    }

    private JsonObject addPattern(String className, String parentPath) {
      if (className == null) {
        throw new ToolRefusal("adding a pattern needs \"class\"; find it with lx_catalog");
      }

      final LXChannel channel = requireChannel(parentPath);
      final int before = channel.patterns.size();

      final LXCommand command = new LXCommand.Channel.AddPattern(channel, patternClass(className));
      this.lx.command.perform(command);

      if (channel.patterns.size() != before + 1) {
        throw new ToolRefusal("the pattern was not added; check the Chromatik log for why");
      }

      final LXPattern added = channel.patterns.get(before);

      final JsonObject result = new JsonObject();
      result.addProperty("created", added.getCanonicalPath());
      result.addProperty("label", added.getLabel());
      result.add("params", curatedValues(added));
      addWarnings(result, added);
      result.addProperty("undo", command.getDescription());

      return result;
    }

    private JsonObject addEffect(String className, String parentPath) {
      if (className == null) {
        throw new ToolRefusal("adding an effect needs \"class\"; find it with lx_catalog");
      }

      final LXComponent parent = parentPath == null
          ? this.lx.engine.mixer.masterBus
          : requireComponent(parentPath);

      if (!(parent instanceof heronarts.lx.mixer.LXBus bus)) {
        throw new ToolRefusal(parentPath + " is not a channel or the master bus");
      }

      final int before = bus.effects.size();

      final LXCommand command = new LXCommand.Channel.AddEffect(bus, effectClass(className));
      this.lx.command.perform(command);

      if (bus.effects.size() != before + 1) {
        throw new ToolRefusal("the effect was not added; check the Chromatik log for why");
      }

      final LXEffect added = bus.effects.get(before);

      final JsonObject result = new JsonObject();
      result.addProperty("created", added.getCanonicalPath());
      result.addProperty("label", added.getLabel());
      result.add("params", curatedValues(added));
      result.addProperty("undo", command.getDescription());

      return result;
    }

    /**
     * Flag a freshly added component that cannot render yet.
     *
     * <p>A video pattern with no file paints its background colour, which looks exactly like a
     * broken projection. Saying so here costs a few tokens and saves the agent a render, a look,
     * and a wrong conclusion about its own parameters.
     */
    private void addWarnings(JsonObject result, LXComponent component) {
      for (LXParameter parameter : component.getParameters()) {
        if (parameter instanceof StringParameter text
            && "file".equals(parameter.getPath())
            && (text.getString() == null || text.getString().isBlank())) {

          result.addProperty("warn", "\"file\" is empty, so this pattern renders its background "
              + "until you set a video path");
          return;
        }
      }
    }

    private JsonObject curatedValues(LXComponent component) {
      final JsonObject values = new JsonObject();

      for (LXParameter parameter : this.catalog.curated(component)) {
        values.add(parameter.getPath(), this.catalog.valueOf(parameter));
      }

      return values;
    }

    private LXChannel requireChannel(String path) {
      if (path == null) {
        throw new ToolRefusal("adding a pattern needs \"parent\", the channel path");
      }

      final LXComponent component = requireComponent(path);

      if (!(component instanceof LXChannel channel)) {
        throw new ToolRefusal(path + " is not a pattern channel");
      }

      return channel;
    }

    private LXComponent requireComponent(String path) {
      final LXComponent component = LXPath.getComponent(this.lx, normalize(path));

      if (component == null) {
        throw new ToolRefusal("no component at " + path + "; call lx_project to see what exists");
      }

      return component;
    }

    /** Look the class up in the registry, so an uninstalled name fails before anything changes. */
    private Class<? extends LXPattern> patternClass(String className) {
      for (Class<? extends LXPattern> candidate : this.lx.registry.patterns) {
        if (candidate.getName().equals(className)) {
          return candidate;
        }
      }

      throw new ToolRefusal(className + " is not an installed pattern; list them with lx_catalog");
    }

    private Class<? extends LXEffect> effectClass(String className) {
      for (Class<? extends LXEffect> candidate : this.lx.registry.effects) {
        if (candidate.getName().equals(className)) {
          return candidate;
        }
      }

      throw new ToolRefusal(className + " is not an installed effect; list them with lx_catalog");
    }
  }

  // ---- lx_undo ------------------------------------------------------------

  /** Walk Chromatik's own undo history. */
  private record UndoTool(LX lx, EngineBridge bridge) implements Tool {

    @Override
    public String name() {
      return "lx_undo";
    }

    @Override
    public String title() {
      return "Undo or redo";
    }

    @Override
    public String description() {
      return "Steps back through Chromatik's undo history, the same one Cmd-Z uses, and reports "
          + "what it reversed. Set redo=true to step forward. Note that a command which fails "
          + "clears the history, so there may be less to undo than you expect.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.object(
          "steps", Schema.integer("How many entries to step.", 1, 64, 1),
          "redo", Schema.bool("Step forward instead of back.", false));
    }

    @Override
    public boolean readOnly() {
      return false;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      final int steps = Args.optionalInt(arguments, "steps", 1);
      final boolean redo = Args.optionalBoolean(arguments, "redo", false);

      return this.bridge.call("lx_undo", () -> {
        final JsonArray stepped = new JsonArray();

        for (int taken = 0; taken < steps; taken++) {
          // Read the description before performing: afterwards it has moved to the other stack.
          final LXCommand next = redo ? this.lx.command.getRedoCommand() : this.lx.command.getUndoCommand();

          if (next == null) {
            break;
          }

          stepped.add(next.getDescription());

          if (redo) {
            this.lx.command.redo();
          } else {
            this.lx.command.undo();
          }
        }

        final JsonObject result = new JsonObject();
        result.add(redo ? "redone" : "undone", stepped);

        final LXCommand remaining = this.lx.command.getUndoCommand();
        result.addProperty("nextUndo", remaining != null ? remaining.getDescription() : null);

        if (stepped.isEmpty()) {
          result.addProperty("note", redo
              ? "nothing to redo"
              : "nothing to undo; the history is empty or was cleared by a failed command");
        }

        return result;
      });
    }
  }

  /** LX paths are absolute and rooted at /lx. Tolerate a missing leading slash. */
  private static String normalize(String path) {
    final String trimmed = path.trim();

    return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  private WriteTools() {
  }
}
