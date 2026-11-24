package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Kind;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Remap;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Value;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Captures one reader's typed host source before either system or emitter VM execution. */
public final class NiagaraObjectPropertyReader {
    public enum Mode { Binding, AttachParentActor, BindingThenAttachParentActor, AttachParent, BindingThenAttachParent }
    public record Definition(Mode mode, String objectBinding, String sourceActor, String componentClass,
                             Map<String, String> remaps, @Nullable Source savedActor) {
        public Definition { remaps = Map.copyOf(remaps); }

        public static Definition read(JsonObject data) {
            var remaps = new HashMap<String, String>();
            for (var entry : data.getAsJsonArray("remaps")) {
                var item = entry.getAsJsonObject();
                remaps.putIfAbsent(SourceNames.canonical(item.get("graph").getAsString()), item.get("property").getAsString());
            }
            String componentClass = data.get("component_class").getAsString();
            Source actor = null;
            if (data.has("source_actor_data")) {
                var saved = data.getAsJsonObject("source_actor_data");
                var root = readSavedComponent(saved, "root_fields", "root_transform");
                var selected = readSavedComponent(saved, "component_fields", "component_transform");
                var components = selected == null ? Map.<String, Source>of() : Map.of(componentClass, selected);
                actor = new Source(readSavedFields(saved.getAsJsonObject("fields")).properties(),
                        root == null ? null : root.transform(), root, components);
            } else if (data.has("source_actor_fields")) actor = readSavedFields(data.getAsJsonObject("source_actor_fields"));
            return new Definition(Mode.valueOf(data.get("mode").getAsString()), data.get("binding").getAsString(),
                    data.get("source_actor").getAsString(), componentClass, remaps, actor);
        }
    }

    private static @Nullable Source readSavedComponent(JsonObject object, String fields, String transform) {
        if (!object.has(fields)) return null;
        return new Source(readSavedFields(object.getAsJsonObject(fields)).properties(), savedSceneTransform(object, transform));
    }

    static @Nullable Transform savedSceneTransform(JsonObject object, String transform) {
        var saved = object.get(transform);
        if (saved == null || !saved.isJsonObject()) return null;
        var component = SceneComponentTransform.authored(saved.getAsJsonObject());
        var matrix = component.matrix();
        var rotation = component.rotation(new Quaterniond());
        var scale = component.scale(new Vector3d());
        return new Transform(matrix.m30(), matrix.m31(), matrix.m32(), rotation.x, rotation.y, rotation.z, rotation.w,
                scale.x, scale.y, scale.z);
    }

    public static Source readSavedFields(JsonObject fields) {
        var properties = new HashMap<String, Value>();
        fields.entrySet().forEach(entry -> {
            var field = entry.getValue().getAsJsonObject();
            String type = field.get("type").getAsString();
            var values = field.getAsJsonArray("components");
            double[] components = new double[values.size()];
            for (int index = 0; index < components.length; index++) components[index] = values.get(index).getAsDouble();
            Kind kind;
            if (type.equals("COLOR_SRGB8")) {
                kind = Kind.COLOR;
                if (components.length != kind.width()) throw new IllegalArgumentException("Invalid saved Niagara byte color");
                for (int index = 0; index < 3; index++) components[index] = SourceColorMath.linear((int)components[index]);
                components[3] = (float)components[3] * (1.0f / 255.0f);
            } else kind = Kind.valueOf(type);
            properties.put(entry.getKey(), new Value(kind, components));
        });
        return new Source(properties, null);
    }

    public record Request(@Nullable Kind kind, String propertyName, boolean inverseTransform) {
        public int outputs() { return kind == null ? 11 : 1 + kind.width(); }

        public static Request read(String function, JsonObject data) {
            if (function.equals("GetComponentTransform")) return new Request(null, "", false);
            if (function.equals("GetComponentInverseTransform")) return new Request(null, "", true);
            Kind kind = switch (function) {
                case "GetFloatProperty" -> Kind.FLOAT;
                case "GetVec2Property" -> Kind.VEC2;
                case "GetVec3Property" -> Kind.VEC3;
                case "GetVec4Property" -> Kind.VEC4;
                case "GetColorProperty" -> Kind.COLOR;
                case "GetPositionProperty" -> Kind.POSITION;
                case "GetIntProperty" -> Kind.INT;
                case "GetBoolProperty" -> Kind.BOOL;
                default -> throw new IllegalArgumentException("Unknown Niagara object reader function " + function);
            };
            return new Request(kind, data.get("property").getAsString(), false);
        }
    }

    private record Property(Kind kind, String name) { }
    private record InstanceData(Map<String, Value> object, Map<String, Value> component, @Nullable Transform transform,
                                double originX, double originY, double originZ, Map<Property, int[]> sampled) { }
    private static final InstanceData EMPTY = new InstanceData(Map.of(), Map.of(), null, 0, 0, 0, Map.of());
    private static final Source OPAQUE_OBJECT = new Source(Map.of(), null);
    public static final class Snapshot {
        private final InstanceData data;
        private final Map<String, Remap> remaps;
        private final Map<String, String> defaults;

        private Snapshot(InstanceData data, Map<String, Remap> remaps, Map<String, String> defaults) {
            this.data = data;
            this.remaps = remaps;
            this.defaults = defaults;
        }

        public String propertyName(Request request) { return resolvedPropertyName(request, remaps, defaults); }
        public int[] read(Request request) { return NiagaraObjectPropertyReader.read(request, data, remaps, defaults); }
    }
    private final Definition definition;
    private final LinkedHashSet<Request> requests = new LinkedHashSet<>();
    private Map<String, Remap> requestedRemaps = Map.of();
    private Map<String, Remap> initializedRemaps = requestedRemaps;
    private volatile InstanceData instance = EMPTY;

    public NiagaraObjectPropertyReader(Definition definition) { this.definition = definition; }
    public Definition definition() { return definition; }
    public void register(Request request) { if (request.kind() != null) requests.add(request); }

    public void remaps(Map<String, Remap> remaps) {
        if (requestedRemaps.size() == remaps.size() && requestedRemaps.entrySet().stream()
                .allMatch(entry -> remaps.get(entry.getKey()) == entry.getValue())) return;
        requestedRemaps = Map.copyOf(remaps);
    }

    /** Mirrors the source PreTick request. A newly initialized reader samples on the next PreTick. */
    public boolean preTick(ObjectPropertyInputs inputs, @Nullable NiagaraParameterStore parameters,
                           Function<Object, @Nullable Source> savedObjects, double x, double y, double z) {
        if (initializedRemaps != requestedRemaps) return true;
        var mode = definition.mode();
        Source object = null;
        if (mode != Mode.AttachParent && mode != Mode.AttachParentActor) {
            String binding = definition.objectBinding();
            object = inputs.objects().get(binding);
            if (object == null && parameters != null && !binding.isEmpty()) {
                var bound = parameters.objectBinding(binding).value();
                if (bound != null) {
                    String identity = NiagaraParameterStoreSources.objectIdentity(bound);
                    if (identity != null) object = inputs.objects().get(identity);
                    if (object == null) object = savedObjects.apply(bound);
                    if (object == null) object = OPAQUE_OBJECT;
                }
            }
            if (object == null) object = inputs.objects().get(definition.sourceActor());
            if (object == null) object = definition.savedActor();
        }
        if (object == null) object = switch (mode) {
            case AttachParent, BindingThenAttachParent -> inputs.parentComponent();
            case AttachParentActor, BindingThenAttachParentActor -> inputs.parentActor();
            default -> null;
        };
        var component = object == null ? null : definition.componentClass().isEmpty()
                ? object.root() : object.components().get(definition.componentClass());
        var transform = component != null && component.transform() != null ? component.transform()
                : object == null ? null : object.transform();
        var objectFields = object == null ? Map.<String, Value>of() : object.properties();
        var componentFields = component == null ? Map.<String, Value>of() : component.properties();
        var captured = new InstanceData(objectFields, componentFields, transform, x, y, z, Map.of());
        var samples = new HashMap<Property, int[]>();
        for (var request : requests) {
            Kind kind = request.kind();
            String name = resolvedPropertyName(request, initializedRemaps, definition.remaps());
            var key = new Property(kind, SourceNames.canonical(name));
            if (samples.containsKey(key)) continue;
            var value = sample(object, name, kind);
            if (value == null) value = sample(component, name, kind);
            samples.put(key, encode(kind, captured, value));
        }
        instance = new InstanceData(objectFields, componentFields, transform, x, y, z, Map.copyOf(samples));
        return false;
    }

    public void reinitialize() {
        initializedRemaps = requestedRemaps;
        instance = EMPTY;
    }

    public int[] read(Request request) {
        return read(request, instance, initializedRemaps, definition.remaps());
    }

    public Snapshot snapshot() { return new Snapshot(instance, initializedRemaps, definition.remaps()); }

    private static String resolvedPropertyName(Request request, Map<String, Remap> remaps, Map<String, String> defaults) {
        String key = SourceNames.canonical(request.propertyName());
        var remap = remaps.get(key);
        return remap == null ? defaults.getOrDefault(key, request.propertyName()) : remap.propertyName();
    }

    private static int[] read(Request request, InstanceData captured, Map<String, Remap> remaps, Map<String, String> defaults) {
        Kind kind = request.kind();
        if (kind == null) return transform(captured.transform(), request.inverseTransform());
        String name = resolvedPropertyName(request, remaps, defaults);
        int[] sampled = captured.sampled().get(new Property(kind, SourceNames.canonical(name)));
        if (sampled != null) return sampled.clone();
        var value = compatible(captured.object().get(name), kind);
        if (value == null) value = compatible(captured.component().get(name), kind);
        return encode(kind, captured, value);
    }

    private static int[] encode(Kind kind, InstanceData captured, @Nullable Value value) {
        int[] words = new int[1 + kind.width()];
        if (value == null) {
            if (kind == Kind.COLOR) words[4] = bits(1.0);
            return words;
        }
        words[0] = -1;
        double[] components = value.components();
        if (kind == Kind.POSITION) {
            components[0] -= captured.originX();
            components[1] -= captured.originY();
            components[2] -= captured.originZ();
        }
        for (int index = 0; index < components.length; index++)
            words[index + 1] = switch (kind) {
                case INT -> (int)components[index];
                case BOOL -> components[index] == 0 ? 0 : -1;
                default -> bits(components[index]);
            };
        return words;
    }

    private static @Nullable Value sample(@Nullable Source source, String name, Kind kind) {
        if (source == null) return null;
        var getter = source.getters().get(name);
        if (getter != null) return compatible(getter.kind(), kind) ? getter.sample() : null;
        return compatible(source.properties().get(name), kind);
    }

    private static @Nullable Value compatible(@Nullable Value value, Kind kind) {
        if (value == null) return null;
        return compatible(value.kind(), kind) ? value : null;
    }

    private static boolean compatible(Kind actual, Kind expected) {
        return actual == expected || actual == Kind.POSITION && expected == Kind.VEC3
                || actual == Kind.VEC3 && expected == Kind.POSITION;
    }

    private static int[] transform(@Nullable Transform source, boolean inverse) {
        if (source == null) return new int[]{0, 0, 0, 0, 0, 0, 0, bits(1), bits(1), bits(1), bits(1)};
        var position = new Vector3d(source.x(), source.y(), source.z());
        var rotation = new Quaterniond(source.qx(), source.qy(), source.qz(), source.qw());
        double sx = source.scaleX(), sy = source.scaleY(), sz = source.scaleZ();
        if (inverse) {
            sx = reciprocal(sx); sy = reciprocal(sy); sz = reciprocal(sz);
            rotation.conjugate();
            rotation.transform(position.mul(-sx, -sy, -sz));
        }
        return new int[]{-1, bits(position.x), bits(position.y), bits(position.z),
                bits(rotation.x), bits(rotation.y), bits(rotation.z), bits(rotation.w), bits(sx), bits(sy), bits(sz)};
    }

    private static double reciprocal(double value) { return Math.abs(value) <= 1.0e-8 ? 0.0 : 1.0 / value; }
    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
}
