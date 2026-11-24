package com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.component.ComponentTickWindow;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs.Socket;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSocketInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** The game-thread source selection and CPU/GPU socket frame of a Niagara system instance. */
public final class NiagaraSocketReader {
    public enum Mode { Default, ParameterBindingOnly, AttachedParentOnly, SourceOnly }
    public record Definition(Mode mode, String parameter, @Nullable String actor, @Nullable String attachmentClass, String attachmentTag,
                             List<String> filtered, boolean updateSockets, boolean requireCurrent,
                             @Nullable Identifier staticMesh, @Nullable Identifier skeletalMesh) {
        public Definition { filtered = List.copyOf(filtered); }
        public static Definition read(JsonObject value) {
            var names = new ArrayList<String>();
            for (var item : value.getAsJsonArray("filtered")) names.add(item.getAsString());
            return new Definition(Mode.valueOf(value.get("mode").getAsString()), value.get("parameter").getAsString(),
                    string(value, "source_actor"), string(value, "attachment_class"), value.get("attachment_tag").getAsString(),
                    names, value.get("update_sockets").getAsBoolean(), value.get("require_current").getAsBoolean(),
                    identifier(value, "static_mesh_parameter"), identifier(value, "skeletal_mesh_parameter"));
        }
    }

    public enum Function {
        IsValid, GetSocketCount, GetFilteredSocketCount, GetUnfilteredSocketCount, GetComponentToWorld,
        GetSocketTransform, GetFilteredSocketTransform, GetUnfilteredSocketTransform,
        GetSocketTransformInterpolated, GetFilteredSocketTransformInterpolated, GetUnfilteredSocketTransformInterpolated
    }
    @FunctionalInterface public interface Output { void set(int word, int lane, int value); }
    private record Selection(Object identity, @Nullable Transform component, Supplier<List<Socket>> sockets, @Nullable ComponentTickWindow tickWindow) { }
    private record MeshIdentity(Object source, @Nullable Object mesh) { }

    public static final class Transforms {
        private final int[] indices;
        private final List<String> names;
        private final List<Transform> current, previous;
        private final int filtered, unfiltered;

        private Transforms(int[] indices, List<String> names, List<Transform> current, List<Transform> previous, int filtered, int unfiltered) {
            this.indices = indices;
            this.names = names;
            this.current = current;
            this.previous = previous;
            this.filtered = filtered;
            this.unfiltered = unfiltered;
        }
        public int sockets() { return current.size() - 1; }
        public int filtered() { return filtered; }
        public int unfiltered() { return unfiltered; }
        public int socketOffset() { return indices.length * Integer.BYTES; }
        public int previousOffset() { return Math.addExact(socketOffset(), Math.multiplyExact(current.size(), NiagaraSocketTransforms.WORDS * Float.BYTES)); }
        public int bytes() {
            int count = Math.addExact(previousOffset(), Math.multiplyExact(previous.size(), NiagaraSocketTransforms.WORDS * Float.BYTES));
            return Math.addExact(count, 15) & -16;
        }
        public void write(ByteBuffer output) {
            for (int index : indices) output.putInt(index);
            for (var transform : current) NiagaraSocketTransforms.write(output, transform);
            for (var transform : previous) NiagaraSocketTransforms.write(output, transform);
            while (output.hasRemaining()) output.put((byte)0);
        }
    }

    public record Snapshot(boolean valid, float inverseDelta, Transform component, Transform previousComponent, Transforms transforms) { }
    private static final Transform IDENTITY = NiagaraSocketTransforms.IDENTITY;
    private static final Transforms EMPTY_TRANSFORMS = new Transforms(new int[] {0}, List.of(), List.of(IDENTITY), List.of(IDENTITY), 0, 0);
    private final Definition definition;
    private @Nullable Object identity;
    private @Nullable Transform lastComponent;
    private volatile Snapshot snapshot = new Snapshot(false, 0, IDENTITY, IDENTITY, EMPTY_TRANSFORMS);

    public NiagaraSocketReader(Definition definition) { this.definition = definition; }
    public Snapshot snapshot() { return snapshot; }
    public String parameter() { return definition.parameter(); }
    public ComponentTickGroup requiredTickGroup(NiagaraSocketInputs inputs, NiagaraStaticMeshInputs staticInputs, NiagaraSkeletalMeshInputs skeletalInputs,
                                  @Nullable NiagaraParameterStore parameters, Map<Identifier, StaticMeshAsset> staticAssets,
                                  Map<Identifier, SkeletalMeshAsset> skeletalAssets, boolean explicitOverride,
                                  java.util.function.Function<Object, ActorComponentInputs.@Nullable Binding> savedActors) {
        if (!definition.requireCurrent()) return ComponentTickGroup.TG_PrePhysics;
        var source = select(inputs, staticInputs, skeletalInputs, parameters, staticAssets, skeletalAssets, explicitOverride, savedActors);
        var window = source == null || source.component() == null ? null : source.tickWindow();
        return window == null ? ComponentTickGroup.TG_PrePhysics : window.afterComponent();
    }

    public void capture(NiagaraSocketInputs inputs, NiagaraStaticMeshInputs staticInputs, NiagaraSkeletalMeshInputs skeletalInputs,
                        @Nullable NiagaraParameterStore parameters, Map<Identifier, StaticMeshAsset> staticAssets,
                        Map<Identifier, SkeletalMeshAsset> skeletalAssets, Pose pose, float delta, double x, double y, double z) {
        capture(inputs, staticInputs, skeletalInputs, parameters, staticAssets, skeletalAssets, false, ignored -> null, pose, delta, x, y, z);
    }

    public void capture(NiagaraSocketInputs inputs, NiagaraStaticMeshInputs staticInputs, NiagaraSkeletalMeshInputs skeletalInputs,
                        @Nullable NiagaraParameterStore parameters, Map<Identifier, StaticMeshAsset> staticAssets,
                        Map<Identifier, SkeletalMeshAsset> skeletalAssets, boolean explicitOverride,
                        java.util.function.Function<Object, ActorComponentInputs.@Nullable Binding> savedActors,
                        Pose pose, float delta, double x, double y, double z) {
        var source = select(inputs, staticInputs, skeletalInputs, parameters, staticAssets, skeletalAssets, explicitOverride, savedActors);
        Object selected = source == null ? null : source.identity();
        boolean reset = !Objects.equals(identity, selected) || lastComponent == null;
        var transform = source == null || source.component() == null ? NiagaraSocketTransforms.owner(pose) : source.component();
        var prior = reset ? transform : Objects.requireNonNull(lastComponent);
        var data = snapshot.transforms();
        if (source == null) data = EMPTY_TRANSFORMS;
        else if (reset || definition.updateSockets()) {
            var sockets = source.sockets().get();
            var current = new ArrayList<Transform>(sockets.size() + 1);
            for (var socket : sockets) current.add(socket.local());
            current.add(IDENTITY);
            List<Transform> next = List.copyOf(current);
            var names = sockets.stream().map(socket -> SourceNames.canonical(socket.name())).toList();
            if (reset || !names.equals(data.names)) data = filters(names, next);
            else if (!next.equals(data.current) || !data.current.equals(data.previous))
                data = new Transforms(data.indices, data.names, next, data.current, data.filtered, data.unfiltered);
        }
        identity = selected;
        lastComponent = transform;
        snapshot = new Snapshot(source != null, delta > 0 ? 1f / delta : 0f,
                NiagaraSocketTransforms.translated(transform, x, y, z), NiagaraSocketTransforms.translated(prior, x, y, z), data);
    }

    private @Nullable Selection select(NiagaraSocketInputs inputs, NiagaraStaticMeshInputs staticInputs, NiagaraSkeletalMeshInputs skeletalInputs,
                                       @Nullable NiagaraParameterStore parameters, Map<Identifier, StaticMeshAsset> staticAssets,
                                       Map<Identifier, SkeletalMeshAsset> skeletalAssets, boolean explicitOverride,
                                       java.util.function.Function<Object, ActorComponentInputs.@Nullable Binding> savedActors) {
        Mode mode = definition.mode();
        if (mode == Mode.Default || mode == Mode.ParameterBindingOnly) {
            String name = definition.parameter();
            if (!name.isEmpty() && !SourceNames.equal(name, "None")) {
                if (!explicitOverride) {
                    var provided = inputs.parameters().get(name);
                    if (provided != null) return host(provided);
                    var skinned = skeletalInputs.parameters().get(name);
                    if (skinned != null && available(skinned)) return skeletal(name, skinned);
                    var rigid = staticInputs.parameters().get(name);
                    if (rigid != null && available(rigid)) return rigid(name, rigid);
                }
                var value = parameters == null ? null : parameters.objectBinding(name).value();
                if (value != null) {
                    var selected = parameterObject(inputs, staticInputs, skeletalInputs, staticAssets, skeletalAssets, value, savedActors.apply(value));
                    if (selected != null) return selected;
                }
            }
        }
        if (mode == Mode.Default || mode == Mode.AttachedParentOnly) {
            for (var attached : inputs.attachments()) if (accepts(attached.classes(), attached.tags())) return host(attached);
            for (int index = 0; index < skeletalInputs.attachments().size(); index++) {
                var attached = skeletalInputs.attachments().get(index);
                if (available(attached) && accepts(attached.componentClasses(), attached.tags())) return skeletal(index, attached);
            }
            var attached = staticInputs.attachment();
            if (attached != null && available(attached) && accepts(List.of("/Script/Engine.StaticMeshComponent", "/Script/Engine.SceneComponent"), List.of()))
                return rigid("attach", attached);
        }
        if (mode == Mode.Default || mode == Mode.SourceOnly) {
            String actor = definition.actor();
            if (actor != null) {
                var provided = inputs.actors().get(actor);
                if (provided != null) return host(provided);
                var rigid = staticInputs.actors().get(actor);
                if (rigid != null && available(rigid)) return rigid(actor, rigid);
                var skinned = skeletalInputs.actors().get(actor);
                if (skinned != null && !skinned.isEmpty() && available(skinned.getFirst())) return skeletal(actor, skinned.getFirst());
            }
            return asset(definition.staticMesh(), definition.skeletalMesh(), staticAssets, skeletalAssets);
        }
        return null;
    }

    private static @Nullable Selection parameterObject(NiagaraSocketInputs inputs, NiagaraStaticMeshInputs staticInputs,
                                                       NiagaraSkeletalMeshInputs skeletalInputs, Map<Identifier, StaticMeshAsset> staticAssets,
                                                       Map<Identifier, SkeletalMeshAsset> skeletalAssets, Object value,
                                                       ActorComponentInputs.@Nullable Binding savedBinding) {
        String path = NiagaraParameterStoreSources.sourceObjectPath(value);
        if (!path.isEmpty()) {
            var provided = inputs.parameters().get(path);
            if (provided == null) provided = inputs.actors().get(path);
            if (provided != null) return host(provided);
            var rigid = staticInputs.components().get(path);
            if (rigid == null) rigid = staticInputs.actors().get(path);
            if (rigid != null && available(rigid)) return rigid(path, rigid);
            var skinned = skeletalInputs.components().get(path);
            if (skinned == null) {
                var actor = skeletalInputs.actors().get(path);
                if (actor != null && !actor.isEmpty()) skinned = actor.getFirst();
            }
            if (skinned != null && available(skinned)) return skeletal(path, skinned);
        }
        if (value instanceof ObjectValue object) {
            var mesh = asset(object.source(), staticAssets, skeletalAssets);
            if (mesh != null) return mesh;
        }
        var component = savedBinding instanceof ActorComponentInputs.Actor actor ? actor.root()
                : savedBinding instanceof ActorComponentInputs.Component saved ? saved : null;
        if (component != null) {
            return new Selection(value, component.sceneTransform(), List::of, null);
        }
        if (savedBinding instanceof ActorComponentInputs.Actor) return null;
        return new Selection(value, null, List::of, null);
    }

    private boolean accepts(List<String> classes, List<String> tags) {
        return (definition.attachmentClass() == null || classes.stream().anyMatch(value -> SourceNames.equal(value, definition.attachmentClass())))
                && (definition.attachmentTag().isEmpty() || SourceNames.equal(definition.attachmentTag(), "None")
                || tags.stream().anyMatch(value -> SourceNames.equal(value, definition.attachmentTag())));
    }

    private static Selection host(Source source) { return new Selection(source.identity(), source.component(), source::sockets, source.tickWindow()); }
    private static boolean available(NiagaraStaticMeshInputs.Source source) { return source.mesh() != null || source.component() != null; }
    private static boolean available(NiagaraSkeletalMeshInputs.Component source) { return source.mesh() != null || source.transform() != null; }

    private static Selection rigid(Object key, NiagaraStaticMeshInputs.Source source) {
        var mesh = source.mesh();
        var identity = Objects.requireNonNullElse(source.componentIdentity(), key);
        return new Selection(new MeshIdentity(identity, mesh), source.component(),
                () -> mesh == null ? List.of() : NiagaraSocketTransforms.asset(mesh), source.tickWindow());
    }
    private static Selection skeletal(Object key, NiagaraSkeletalMeshInputs.Component source) {
        var mesh = source.mesh();
        var identity = Objects.requireNonNullElse(source.componentIdentity(), key);
        return new Selection(new MeshIdentity(identity, mesh), source.transform(),
                () -> mesh == null ? List.of() : NiagaraSocketTransforms.asset(mesh, source.componentPose()), source.tickWindow());
    }
    private static @Nullable Selection asset(JsonObject value, Map<Identifier, StaticMeshAsset> rigid, Map<Identifier, SkeletalMeshAsset> skeletal) {
        return asset(identifier(value, "static_mesh_parameter"), identifier(value, "skeletal_mesh_parameter"), rigid, skeletal);
    }
    private static @Nullable Selection asset(@Nullable Identifier rigidId, @Nullable Identifier skinnedId,
                                             Map<Identifier, StaticMeshAsset> rigid, Map<Identifier, SkeletalMeshAsset> skeletal) {
        if (rigidId != null) {
            var mesh = Objects.requireNonNull(rigid.get(rigidId), "Missing prepared Socket Reader mesh " + rigidId);
            return new Selection(rigidId, null, () -> NiagaraSocketTransforms.asset(mesh), null);
        }
        if (skinnedId != null) {
            var mesh = Objects.requireNonNull(skeletal.get(skinnedId), "Missing prepared Socket Reader mesh " + skinnedId);
            return new Selection(skinnedId, null, () -> NiagaraSocketTransforms.asset(mesh, List.of()), null);
        }
        return null;
    }

    private Transforms filters(List<String> names, List<Transform> values) {
        int count = names.size();
        if (count == 0) return EMPTY_TRANSFORMS;
        var byName = new HashMap<String, Integer>();
        for (int index = 0; index < count; index++) byName.putIfAbsent(names.get(index), index);
        var indices = new ArrayList<Integer>(1 + definition.filtered().size() + count);
        indices.add(count);
        boolean[] filtered = new boolean[count];
        for (String name : definition.filtered()) {
            int index = byName.getOrDefault(SourceNames.canonical(name), count);
            indices.add(index);
            if (index < count) filtered[index] = true;
        }
        int firstUnfiltered = indices.size();
        for (int index = 0; index < count; index++) if (!filtered[index]) indices.add(index);
        return new Transforms(indices.stream().mapToInt(Integer::intValue).toArray(), names, values, values,
                definition.filtered().size(), indices.size() - firstUnfiltered);
    }

    public void execute(Function function, int count, IntBinaryOperator input, Output output) {
        var frame = snapshot;
        var data = frame.transforms();
        for (int lane = 0; lane < count; lane++) switch (function) {
            case IsValid -> output.set(0, lane, frame.valid() ? -1 : 0);
            case GetSocketCount -> output.set(0, lane, data.sockets());
            case GetFilteredSocketCount -> output.set(0, lane, data.filtered());
            case GetUnfilteredSocketCount -> output.set(0, lane, data.unfiltered());
            case GetComponentToWorld -> transform(frame.component(), lane, output);
            case GetSocketTransform, GetFilteredSocketTransform, GetUnfilteredSocketTransform,
                    GetSocketTransformInterpolated, GetFilteredSocketTransformInterpolated, GetUnfilteredSocketTransformInterpolated -> {
                int index = input.applyAsInt(0, lane);
                int selected = switch (function) {
                    case GetFilteredSocketTransform, GetFilteredSocketTransformInterpolated -> data.indices[index >= 0 && index < data.filtered() ? 1 + index : 0];
                    case GetUnfilteredSocketTransform, GetUnfilteredSocketTransformInterpolated -> data.indices[index >= 0 && index < data.unfiltered() ? 1 + data.filtered() + index : 0];
                    default -> index >= 0 && index < data.sockets() ? index : data.sockets();
                };
                boolean interpolate = switch (function) {
                    case GetSocketTransformInterpolated, GetFilteredSocketTransformInterpolated, GetUnfilteredSocketTransformInterpolated -> true;
                    default -> false;
                };
                var now = NiagaraSocketTransforms.compose(data.current.get(selected), frame.component());
                var previous = NiagaraSocketTransforms.compose(data.previous.get(selected), frame.previousComponent());
                transform(interpolate ? interpolate(previous, now, Float.intBitsToFloat(input.applyAsInt(1, lane))) : now, lane, output);
                output.set(10, lane, bits(((float)now.x() - (float)previous.x()) * frame.inverseDelta()));
                output.set(11, lane, bits(((float)now.y() - (float)previous.y()) * frame.inverseDelta()));
                output.set(12, lane, bits(((float)now.z() - (float)previous.z()) * frame.inverseDelta()));
            }
        }
    }

    private static Transform interpolate(Transform a, Transform b, float alpha) {
        var rotation = new Quaternionf((float)a.qx(), (float)a.qy(), (float)a.qz(), (float)a.qw());
        rotation.slerp(new Quaternionf((float)b.qx(), (float)b.qy(), (float)b.qz(), (float)b.qw()), alpha).normalize();
        return new Transform(lerp(a.x(), b.x(), alpha), lerp(a.y(), b.y(), alpha), lerp(a.z(), b.z(), alpha),
                rotation.x, rotation.y, rotation.z, rotation.w,
                lerp(a.scaleX(), b.scaleX(), alpha), lerp(a.scaleY(), b.scaleY(), alpha), lerp(a.scaleZ(), b.scaleZ(), alpha));
    }

    private static float lerp(double a, double b, float t) { return (float)a + ((float)b - (float)a) * t; }
    private static void transform(Transform value, int lane, Output output) {
        output.set(0, lane, bits(value.x())); output.set(1, lane, bits(value.y())); output.set(2, lane, bits(value.z()));
        output.set(3, lane, bits(value.qx())); output.set(4, lane, bits(value.qy())); output.set(5, lane, bits(value.qz())); output.set(6, lane, bits(value.qw()));
        output.set(7, lane, bits(value.scaleX())); output.set(8, lane, bits(value.scaleY())); output.set(9, lane, bits(value.scaleZ()));
    }
    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
    private static @Nullable Identifier identifier(JsonObject value, String name) {
        var nested = value.getAsJsonObject(name);
        String asset = nested == null ? null : string(nested, "asset");
        return asset == null ? null : Identifier.parse(asset);
    }
    private static @Nullable String string(JsonObject value, String name) {
        var field = value.get(name);
        return field == null || field.isJsonNull() ? null : field.getAsString();
    }
}
