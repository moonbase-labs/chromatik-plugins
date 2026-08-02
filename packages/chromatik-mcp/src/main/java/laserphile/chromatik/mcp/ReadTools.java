package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.LXPath;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.mixer.LXAbstractChannel;
import heronarts.lx.mixer.LXChannel;
import heronarts.lx.model.LXModel;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tools that let an agent work out what it is looking at.
 *
 * <p>Registered before the write tools, because the order a client sees is a weak steer and an
 * agent that orients before it mutates makes better decisions and fewer undo calls.
 */
final class ReadTools {

  /** Longest a catalogue or list response runs before it starts costing more than it tells. */
  private static final int DEFAULT_PAGE = 25;

  static void register(ToolRegistry registry, LX lx, EngineBridge bridge, ParameterCatalog catalog) {
    registry.add(new ProjectTool(lx, bridge));
    registry.add(new CatalogTool(lx, bridge));
    registry.add(new DocsTool(lx, bridge, catalog));
    registry.add(new DeviceTool(lx, bridge, catalog));
  }

  // ---- lx_project ---------------------------------------------------------

  /** What exists right now: the rig, the engine, and the mixer. */
  private record ProjectTool(LX lx, EngineBridge bridge) implements Tool {

    @Override
    public String name() {
      return "lx_project";
    }

    @Override
    public String title() {
      return "Project overview";
    }

    @Override
    public String description() {
      return "What exists in Chromatik right now: the model's size and bounds, engine and tempo "
          + "state, output state, and every mixer channel with its active pattern. Start here. "
          + "Use detail=\"channels\" to also list each channel's patterns and effects by path.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.object("detail",
          Schema.choice("\"summary\" for the channel list, \"channels\" to expand each channel's "
              + "patterns and effects.", "summary", "channels"));
    }

    @Override
    public boolean readOnly() {
      return true;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      final boolean expand = "channels".equals(Args.optionalString(arguments, "detail", "summary"));

      return this.bridge.call("lx_project", () -> {
        final JsonObject project = new JsonObject();
        project.add("project", describeProjectFile());
        project.add("model", describeModel(this.lx.getModel()));
        project.add("engine", describeEngine());
        project.add("channels", describeChannels(expand));

        return project;
      });
    }

    private JsonObject describeProjectFile() {
      final JsonObject described = new JsonObject();
      final var file = this.lx.getProject();

      // The name only. The absolute path is the user's home directory, which is noise in the
      // model's context and not something it can act on.
      described.addProperty("file", file != null ? file.getName() : null);

      return described;
    }

    private JsonObject describeEngine() {
      final JsonObject described = new JsonObject();
      described.addProperty("fps", Args.tidy(this.lx.engine.getActualFrameRate()));
      described.addProperty("speed", Args.tidy(this.lx.engine.speed.getValue()));
      described.addProperty("bpm", Args.tidy(this.lx.engine.tempo.bpm.getValue()));

      // Whether anything reaches real hardware. An agent that does not know this cannot tell a
      // dark rig from a dark preview.
      final JsonObject output = new JsonObject();
      output.addProperty("enabled", this.lx.engine.output.enabled.isOn());
      output.addProperty("brightness", Args.tidy(this.lx.engine.output.brightness.getValue()));
      described.add("output", output);

      return described;
    }

    private JsonArray describeChannels(boolean expand) {
      final JsonArray channels = new JsonArray();
      int index = 1;

      for (LXAbstractChannel bus : this.lx.engine.mixer.channels) {
        final JsonObject described = new JsonObject();
        described.addProperty("index", index++);
        described.addProperty("path", bus.getCanonicalPath());
        described.addProperty("label", bus.getLabel());
        described.addProperty("enabled", bus.enabled.isOn());
        described.addProperty("fader", Args.tidy(bus.fader.getValue()));

        if (bus instanceof LXChannel channel) {
          final LXPattern active = channel.getActivePattern();
          described.addProperty("activePattern", active != null ? active.getLabel() : null);
          described.addProperty("patterns", channel.patterns.size());

          if (expand) {
            described.add("patternList", describePatterns(channel));
          }
        }

        described.addProperty("effects", bus.effects.size());

        if (expand && !bus.effects.isEmpty()) {
          described.add("effectList", describeEffects(bus));
        }

        channels.add(described);
      }

      return channels;
    }

    private JsonArray describePatterns(LXChannel channel) {
      final JsonArray patterns = new JsonArray();
      final LXPattern active = channel.getActivePattern();

      for (LXPattern pattern : channel.patterns) {
        final JsonObject described = new JsonObject();
        described.addProperty("path", pattern.getCanonicalPath());
        described.addProperty("label", pattern.getLabel());
        described.addProperty("class", pattern.getClass().getName());
        described.addProperty("active", pattern == active);
        patterns.add(described);
      }

      return patterns;
    }

    private JsonArray describeEffects(LXAbstractChannel bus) {
      final JsonArray effects = new JsonArray();

      for (LXEffect effect : bus.effects) {
        final JsonObject described = new JsonObject();
        described.addProperty("path", effect.getCanonicalPath());
        described.addProperty("label", effect.getLabel());
        described.addProperty("class", effect.getClass().getName());
        described.addProperty("enabled", effect.enabled.isOn());
        effects.add(described);
      }

      return effects;
    }
  }

  /**
   * The rig itself.
   *
   * <p>{@code planar} and the bounds are load-bearing rather than decorative: they are how an agent
   * tells a flat test grid from a dome, and that changes every projection parameter it will pick.
   */
  private static JsonObject describeModel(LXModel model) {
    final JsonObject described = new JsonObject();

    if (model == null) {
      described.addProperty("points", 0);
      return described;
    }

    described.addProperty("points", model.size);

    final JsonArray tags = new JsonArray();

    for (String tag : model.tags) {
      tags.add(tag);
    }

    described.add("tags", tags);

    if (model.size == 0) {
      return described;
    }

    float minX = Float.MAX_VALUE;
    float maxX = -Float.MAX_VALUE;
    float minY = Float.MAX_VALUE;
    float maxY = -Float.MAX_VALUE;
    float minZ = Float.MAX_VALUE;
    float maxZ = -Float.MAX_VALUE;

    for (var point : model.points) {
      minX = Math.min(minX, point.x);
      maxX = Math.max(maxX, point.x);
      minY = Math.min(minY, point.y);
      maxY = Math.max(maxY, point.y);
      minZ = Math.min(minZ, point.z);
      maxZ = Math.max(maxZ, point.z);
    }

    final JsonObject bounds = new JsonObject();
    bounds.add("x", span(minX, maxX));
    bounds.add("y", span(minY, maxY));
    bounds.add("z", span(minZ, maxZ));
    described.add("bounds", bounds);

    final float depth = maxZ - minZ;
    final float widest = Math.max(maxX - minX, maxY - minY);

    described.addProperty("planar", depth <= widest * 0.02f);

    return described;
  }

  private static JsonArray span(float low, float high) {
    final JsonArray bounds = new JsonArray();
    bounds.add(Args.tidy(low));
    bounds.add(Args.tidy(high));

    return bounds;
  }

  // ---- lx_catalog ---------------------------------------------------------

  /** What is installed and could be added. */
  private record CatalogTool(LX lx, EngineBridge bridge) implements Tool {

    @Override
    public String name() {
      return "lx_catalog";
    }

    @Override
    public String title() {
      return "Installed components";
    }

    @Override
    public String description() {
      return "Lists the pattern, effect and modulator classes installed in Chromatik, with the "
          + "fully-qualified class name lx_add needs. Filter with q (matches name, class or "
          + "category). Call this before naming a class; do not guess class names.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.object(
          "q", Schema.string("Case-insensitive filter over display name, class name and category."),
          "kind", Schema.choice("Which kind to list.", "all", "pattern", "effect", "modulator"),
          "limit", Schema.integer("Maximum entries to return.", 1, 200, DEFAULT_PAGE),
          "offset", Schema.integer("Entries to skip, for paging.", 0, 100000, 0));
    }

    @Override
    public boolean readOnly() {
      return true;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      final String query = Args.optionalString(arguments, "q", "");
      final String kind = Args.optionalString(arguments, "kind", "all");
      final int limit = Args.optionalInt(arguments, "limit", DEFAULT_PAGE);
      final int offset = Args.optionalInt(arguments, "offset", 0);

      return this.bridge.call("lx_catalog", () -> {
        final List<JsonObject> matches = new ArrayList<>();

        if ("all".equals(kind) || "pattern".equals(kind)) {
          collect(matches, this.lx.registry.patterns, "pattern", query);
        }

        if ("all".equals(kind) || "effect".equals(kind)) {
          collect(matches, this.lx.registry.effects, "effect", query);
        }

        if ("all".equals(kind) || "modulator".equals(kind)) {
          collect(matches, this.lx.registry.modulators, "modulator", query);
        }

        final JsonArray page = new JsonArray();

        for (int index = offset; index < matches.size() && page.size() < limit; index++) {
          page.add(matches.get(index));
        }

        final JsonObject result = new JsonObject();
        result.addProperty("total", matches.size());
        result.add("items", page);

        // Say what was left out. Silent truncation reads as "that is everything".
        if (offset + page.size() < matches.size()) {
          result.addProperty("more", matches.size() - offset - page.size());
          result.addProperty("nextOffset", offset + page.size());
        }

        return result;
      });
    }

    private void collect(List<JsonObject> matches, List<? extends Class<?>> classes, String kind, String query) {
      for (Class<?> type : classes) {
        final String displayName = displayNameOf(type);
        final String category = categoryOf(type);

        if (!matchesQuery(query, displayName, type.getName(), category)) {
          continue;
        }

        final JsonObject described = new JsonObject();
        described.addProperty("name", displayName);
        described.addProperty("class", type.getName());
        described.addProperty("kind", kind);

        if (category != null) {
          described.addProperty("category", category);
        }

        matches.add(described);
      }
    }

    private boolean matchesQuery(String query, String... haystacks) {
      if (query == null || query.isBlank()) {
        return true;
      }

      final String needle = query.toLowerCase(Locale.ROOT);

      for (String haystack : haystacks) {
        if (haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle)) {
          return true;
        }
      }

      return false;
    }
  }

  /** What Chromatik's own browser calls this class. */
  private static String displayNameOf(Class<?> type) {
    final LXComponent.Name name = type.getAnnotation(LXComponent.Name.class);

    return name != null ? name.value() : type.getSimpleName();
  }

  private static String categoryOf(Class<?> type) {
    final LXCategory category = type.getAnnotation(LXCategory.class);

    return category != null ? category.value() : null;
  }

  // ---- lx_docs ------------------------------------------------------------

  /** What a type's parameters are called and what they mean. */
  private record DocsTool(LX lx, EngineBridge bridge, ParameterCatalog catalog) implements Tool {

    @Override
    public String name() {
      return "lx_docs";
    }

    @Override
    public String title() {
      return "Parameter documentation";
    }

    @Override
    public String description() {
      return "Explains a component's parameters: the path key to use, the display label, the Java "
          + "field name when it differs, type, range, enum options and the author's own notes. "
          + "Call this before setting a parameter you have not set before. The path key is often "
          + "not the field name and often not the label, so guessing produces a path that does "
          + "not resolve. Takes an instance path, or a class name from lx_catalog.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.object(
          "path", Schema.string("Canonical path of a live component, e.g. /lx/mixer/channel/1/pattern/1."),
          "class", Schema.string("Fully-qualified class name, for a type not yet added."));
    }

    @Override
    public boolean readOnly() {
      return true;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      final String path = Args.optionalString(arguments, "path", null);
      final String className = Args.optionalString(arguments, "class", null);

      if (path == null && className == null) {
        throw new McpToolException("give either \"path\" for a live component or \"class\" for a type");
      }

      return this.bridge.call("lx_docs", () -> {
        // A live instance is strictly better than a class: its parameters are real objects with
        // real ranges, and no instantiation can fail. Only fall back to the class when there is
        // nothing on the mixer yet.
        final LXComponent component = path != null
            ? requireComponent(this.lx, path)
            : requirePrototype(this.lx, className);

        final JsonObject described = new JsonObject();
        described.addProperty("class", component.getClass().getName());
        described.addProperty("name", displayNameOf(component.getClass()));

        final String category = categoryOf(component.getClass());

        if (category != null) {
          described.addProperty("category", category);
        }

        if (path != null) {
          described.addProperty("path", component.getCanonicalPath());
        }

        final List<LXParameter> documented = this.catalog.documented(component);
        final JsonArray parameters = new JsonArray();
        int knob = 1;

        for (LXParameter parameter : documented) {
          final JsonObject entry = this.catalog.describe(component, parameter, false);

          // The author put the eight most playable parameters first so a MIDI surface binds them
          // to its knobs. Passing that rank through tells the agent what to reach for.
          if (knob <= 8) {
            entry.addProperty("knob", knob);
          }

          knob++;
          parameters.add(entry);
        }

        described.add("params", parameters);
        described.add("omitted", this.catalog.omissions(component, documented.size()));

        return described;
      });
    }
  }

  // ---- lx_device ----------------------------------------------------------

  /** One component's live values. */
  private record DeviceTool(LX lx, EngineBridge bridge, ParameterCatalog catalog) implements Tool {

    @Override
    public String name() {
      return "lx_device";
    }

    @Override
    public String title() {
      return "Component values";
    }

    @Override
    public String description() {
      return "Current parameter values for one component, addressed by canonical path. Defaults to "
          + "the author's curated controls; detail=\"full\" adds everything else the plugin "
          + "declares. Use lx_docs for what the parameters mean.";
    }

    @Override
    public JsonObject inputSchema() {
      return Schema.required(Schema.object(
          "path", Schema.string("Canonical path, e.g. /lx/mixer/channel/1/pattern/1 or /lx/mixer/channel/1."),
          "detail", Schema.choice("\"curated\" for the author's ranked controls, \"full\" for all "
              + "of the plugin's own parameters.", "curated", "full")),
          "path");
    }

    @Override
    public boolean readOnly() {
      return true;
    }

    @Override
    public JsonObject call(JsonObject arguments) throws McpToolException {
      final String path = Args.requireString(arguments, "path");
      final boolean full = "full".equals(Args.optionalString(arguments, "detail", "curated"));

      return this.bridge.call("lx_device", () -> {
        final LXComponent component = requireComponent(this.lx, path);

        final List<LXParameter> shown = full
            ? this.catalog.significant(component)
            : this.catalog.curated(component);

        final JsonObject values = new JsonObject();

        for (LXParameter parameter : shown) {
          values.add(parameter.getPath(), this.catalog.valueOf(parameter));
        }

        final JsonObject described = new JsonObject();
        described.addProperty("path", component.getCanonicalPath());
        described.addProperty("class", component.getClass().getName());
        described.addProperty("label", component.getLabel());
        described.add("values", values);
        described.add("omitted", this.catalog.omissions(component, shown.size()));

        return described;
      });
    }
  }

  // ---- shared lookups -----------------------------------------------------

  /**
   * Resolve a path to a component, or refuse in a way the agent can act on.
   *
   * <p>Raised as a {@link ToolRefusal} because this runs inside an engine task, where the checked
   * form cannot be declared; {@link EngineBridge} unwraps it back into a tool refusal unlogged.
   */
  private static LXComponent requireComponent(LX lx, String path) {
    final LXComponent component = LXPath.getComponent(lx, normalize(path));

    if (component == null) {
      throw new ToolRefusal(
          "no component at " + path + "; call lx_project to see the paths that exist");
    }

    return component;
  }

  private static LXComponent requirePrototype(LX lx, String className) {
    try {
      // Instantiating a pattern to read its parameters is the only way to learn them before it is
      // on a channel. It is never added to the mixer and is disposed immediately.
      return lx.instantiatePattern(className);
    } catch (Exception notAPattern) {
      try {
        return lx.instantiateEffect(className);
      } catch (Exception notAnEffect) {
        throw new ToolRefusal("could not load " + className
            + " as a pattern or effect; check the class name with lx_catalog");
      }
    }
  }

  /** LX paths are absolute and rooted at /lx. Tolerate a missing leading slash. */
  private static String normalize(String path) {
    final String trimmed = path.trim();

    return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
  }

  private ReadTools() {
  }
}
