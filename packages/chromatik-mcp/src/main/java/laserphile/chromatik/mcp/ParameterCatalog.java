package laserphile.chromatik.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import heronarts.lx.LXComponent;
import heronarts.lx.LXDeviceComponent;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.BoundedParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXNormalizedParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.parameter.TriggerParameter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes a component's parameters in a form an agent can act on.
 *
 * <p>This exists because of a trap the repo's own plugins fall into three separate ways. A
 * parameter has a path key, a Java field name, and a display label, and all three can differ:
 * the field {@code wrapMode} is registered under the key {@code wrap} and labelled "Wrap"; the
 * field {@code fileName} is the key {@code file}; the field {@code stretchX} is labelled "XScale".
 * The canonical path uses the key. An agent that reads a README parameter table and guesses the
 * field name builds a path that does not resolve, and the error it gets back says only that the
 * path is unknown. Emitting all three closes that permanently.
 *
 * <p>It also solves the other half of the context problem. A {@code VideoPattern} has 43
 * parameters, of which 20 are inherited chrome that no agent ever wants to see, and the remaining
 * 23 are far more than fit comfortably in a response. Two projections are offered instead of the
 * lot: the author's own {@code setRemoteControls} ordering, which is a hand-curated relevance
 * ranking already sitting in the source, and everything that differs from its default.
 */
final class ParameterCatalog {

  /**
   * Reflection is not free and a component's shape never changes at runtime, so the field map is
   * worked out once per class. Keyed on the class, not the instance.
   */
  private final Map<Class<?>, Map<LXParameter, FieldOrigin>> fieldsByClass = new LinkedHashMap<>();

  /** Where a parameter was declared: its Java field name, and whether LX itself declared it. */
  private record FieldOrigin(String fieldName, boolean inherited) {
  }

  /**
   * Everything known about one parameter of one component.
   *
   * @param includeValue whether to emit the live value, which {@code lx_docs} omits and
   *     {@code lx_device} wants
   */
  JsonObject describe(LXComponent owner, LXParameter parameter, boolean includeValue) {
    final JsonObject described = new JsonObject();

    described.addProperty("key", parameter.getPath());
    described.addProperty("label", parameter.getLabel());

    final FieldOrigin origin = originOf(owner, parameter);

    // Only emitted when it differs from the key. Repeating the key as "field" on every parameter
    // would cost tokens to say nothing; the whole point is to flag the ones that disagree.
    if (origin != null && origin.fieldName() != null && !origin.fieldName().equals(parameter.getPath())) {
      described.addProperty("field", origin.fieldName());
    }

    described.addProperty("type", typeOf(parameter));

    describeRange(described, parameter);

    final String description = parameter.getDescription();

    if (description != null && !description.isBlank()) {
      described.addProperty("doc", description);
    }

    if (includeValue) {
      described.add("value", valueOf(parameter));
    }

    return described;
  }

  /** A short type name the model can reason about, rather than a Java class name. */
  private String typeOf(LXParameter parameter) {
    if (parameter instanceof TriggerParameter) {
      return "trigger";
    }

    if (parameter instanceof BooleanParameter) {
      return "boolean";
    }

    if (parameter instanceof StringParameter) {
      return "string";
    }

    if (parameter instanceof DiscreteParameter discrete) {
      return discrete.getOptions() != null ? "enum" : "integer";
    }

    if (parameter instanceof BoundedParameter) {
      return "number";
    }

    return parameter instanceof LXNormalizedParameter ? "normalized" : "number";
  }

  private void describeRange(JsonObject described, LXParameter parameter) {
    if (parameter instanceof DiscreteParameter discrete) {
      final String[] options = discrete.getOptions();

      if (options != null) {
        // The single most valuable thing here. Enum option labels appear nowhere in OSCQuery, so
        // without this an agent has to guess them, and it cannot.
        final JsonArray allowed = new JsonArray();

        for (String option : options) {
          allowed.add(option);
        }

        described.add("options", allowed);
        return;
      }

      described.add("range", range(discrete.getMinValue(), discrete.getMaxValue()));
      return;
    }

    if (parameter instanceof BoundedParameter bounded) {
      described.add("range", range(bounded.range.min, bounded.range.max));

      final double exponent = bounded.getExponent();

      // A non-linear knob matters to an agent setting a value: on an exponent-2 control, halfway
      // along the range is not half the value.
      if (exponent != 1) {
        described.addProperty("exponent", Args.tidy(exponent));
      }
    }
  }

  private JsonArray range(double minimum, double maximum) {
    final JsonArray bounds = new JsonArray();
    bounds.add(Args.tidy(minimum));
    bounds.add(Args.tidy(maximum));

    return bounds;
  }

  /** The live value, in whatever JSON type reads most naturally for this parameter. */
  com.google.gson.JsonElement valueOf(LXParameter parameter) {
    if (parameter instanceof BooleanParameter flag) {
      return new com.google.gson.JsonPrimitive(flag.isOn());
    }

    if (parameter instanceof StringParameter text) {
      final String value = text.getString();
      return new com.google.gson.JsonPrimitive(value == null ? "" : value);
    }

    if (parameter instanceof DiscreteParameter discrete) {
      final String[] options = discrete.getOptions();

      // By name, never by index. Indices shift when a list changes and an agent that learned one
      // has learned something that quietly stops being true.
      if (options != null) {
        final int index = discrete.getValuei() - discrete.getMinValue();
        return new com.google.gson.JsonPrimitive(
            index >= 0 && index < options.length ? options[index] : String.valueOf(discrete.getValuei()));
      }

      return new com.google.gson.JsonPrimitive(discrete.getValuei());
    }

    return new com.google.gson.JsonPrimitive(Args.tidy(parameter.getBaseValue()));
  }

  /**
   * The parameters worth showing first: the author's own {@code setRemoteControls} order.
   *
   * <p>A pattern that calls it has already ranked its parameters by how much they matter, because
   * the first eight become the knobs on a MIDI surface. Reusing that ranking costs nothing and is
   * better than anything this class could infer.
   */
  List<LXParameter> curated(LXComponent component) {
    if (!(component instanceof LXDeviceComponent device)) {
      return significant(component);
    }

    final var remote = device.getRemoteControls();

    if (remote == null || remote.length == 0) {
      return significant(component);
    }

    final List<LXParameter> ordered = new ArrayList<>(remote.length);

    for (LXParameter parameter : remote) {
      if (parameter != null) {
        ordered.add(parameter);
      }
    }

    return ordered;
  }

  /**
   * Everything worth documenting, most useful first.
   *
   * <p>The curated order, then whatever it left out. Curation alone is not enough for
   * documentation because {@code setRemoteControls} only accepts normalized parameters, so a
   * string or a plain trigger can never appear in it however important it is. On this repo's own
   * video pattern that silently hides {@code file}, without which the pattern renders nothing.
   *
   * <p>{@code lx_device} keeps the curated projection, because that one is called often and wants
   * to stay small. This one is called once per type and wants to be complete.
   */
  List<LXParameter> documented(LXComponent component) {
    final List<LXParameter> ordered = new ArrayList<>(curated(component));
    final Map<LXParameter, Boolean> seen = new IdentityHashMap<>();

    for (LXParameter parameter : ordered) {
      seen.put(parameter, Boolean.TRUE);
    }

    for (LXParameter parameter : significant(component)) {
      if (seen.putIfAbsent(parameter, Boolean.TRUE) == null) {
        ordered.add(parameter);
      }
    }

    return ordered;
  }

  /** Everything except the chrome LX's own base classes declare. */
  List<LXParameter> significant(LXComponent component) {
    final Map<LXParameter, FieldOrigin> origins = originsFor(component);
    final List<LXParameter> significant = new ArrayList<>();

    for (LXParameter parameter : component.getParameters()) {
      final FieldOrigin origin = origins.get(parameter);

      if (origin == null || !origin.inherited()) {
        significant.add(parameter);
      }
    }

    return significant;
  }

  /** How many of a component's parameters each projection leaves out, so the agent knows. */
  JsonObject omissions(LXComponent component, int shown) {
    final int total = component.getParameters().size();
    final int inherited = total - significant(component).size();

    final JsonObject omitted = new JsonObject();
    omitted.addProperty("inherited", inherited);
    omitted.addProperty("notShown", Math.max(0, total - inherited - shown));
    omitted.addProperty("total", total);

    return omitted;
  }

  private FieldOrigin originOf(LXComponent owner, LXParameter parameter) {
    return originsFor(owner).get(parameter);
  }

  private Map<LXParameter, FieldOrigin> originsFor(LXComponent component) {
    return this.fieldsByClass.computeIfAbsent(component.getClass(), type -> mapFields(component));
  }

  /**
   * Match each parameter object back to the field that holds it, by identity.
   *
   * <p>Identity rather than name because the field name is exactly what is being looked up. A
   * parameter is judged inherited when the class declaring its field is one of LX's own: that is
   * precisely the set a plugin author did not write and an agent does not care about.
   *
   * <p>One level of recursion into non-parameter fields, because the shared-parameter-holder idiom
   * this repo uses puts a plugin's most interesting controls one object away from the component.
   */
  private Map<LXParameter, FieldOrigin> mapFields(LXComponent component) {
    final Map<LXParameter, FieldOrigin> origins = new IdentityHashMap<>();

    for (Class<?> type = component.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      final boolean inherited = isLxOwned(type);

      for (Field field : declaredFields(type)) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }

        final Object value = read(field, component);

        if (value instanceof LXParameter parameter) {
          origins.putIfAbsent(parameter, new FieldOrigin(field.getName(), inherited));
        }

        // Recurse even into a parameter, because an aggregate is both. LXDeviceComponent's
        // midiFilter is a MidiFilterParameter whose six sub-parameters are registered on the
        // component under compound keys such as midiFilter/channel; stopping at the aggregate
        // leaves those unclassified, and unclassified defaults to "the plugin author wrote this",
        // which puts LX's per-device MIDI chrome in front of the agent on every component.
        //
        // The other holder shape is a plain object: ProjectionControls in this repo's plugins.
        // Either way the contents inherit the holder's own status, so a plugin's shared control
        // block stays visible while anything LX adds is chrome.
        if (isHolder(value)) {
          mapHolderFields(value, origins, inherited);
        }
      }
    }

    return origins;
  }

  /**
   * Whether an object is worth looking inside for parameters.
   *
   * <p>Excludes the shapes that cannot hold a public parameter field but could be expensive or
   * surprising to walk: containers, arrays, and the JDK's own types.
   */
  private boolean isHolder(Object value) {
    if (value == null || value instanceof Iterable || value instanceof Map || value.getClass().isArray()) {
      return false;
    }

    return !value.getClass().getName().startsWith("java.");
  }

  private void mapHolderFields(Object holder, Map<LXParameter, FieldOrigin> origins, boolean inherited) {
    for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      for (Field field : declaredFields(type)) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }

        if (read(field, holder) instanceof LXParameter parameter) {
          origins.putIfAbsent(parameter, new FieldOrigin(field.getName(), inherited));
        }
      }
    }
  }

  private Field[] declaredFields(Class<?> type) {
    try {
      return type.getDeclaredFields();
    } catch (SecurityException | LinkageError unavailable) {
      return new Field[0];
    }
  }

  /**
   * Read a field, or null.
   *
   * <p>Only public fields are read. LX's own convention is {@code public final} for every
   * parameter, so this reaches everything that matters without calling {@code setAccessible},
   * which on a module-restricted class throws and on any class is a poor habit in a plugin.
   */
  private Object read(Field field, Object target) {
    if (!Modifier.isPublic(field.getModifiers())) {
      return null;
    }

    try {
      return field.get(target);
    } catch (IllegalAccessException | RuntimeException unreadable) {
      return null;
    }
  }

  private boolean isLxOwned(Class<?> type) {
    final String name = type.getName();

    return name.startsWith("heronarts.");
  }
}
