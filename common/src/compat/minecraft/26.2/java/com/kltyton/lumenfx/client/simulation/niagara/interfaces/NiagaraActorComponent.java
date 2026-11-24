package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Actor;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Binding;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Component;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import java.util.function.IntFunction;
import java.util.function.Function;
import org.joml.Matrix4d;
import org.jspecify.annotations.Nullable;

/** Client-thread capture for CPU and GPU Actor Component data interface functions. */
public final class NiagaraActorComponent {
    public enum Mode { Default, AttachParent, LocalPlayer }
    public record Definition(Mode mode, int localPlayerIndex, boolean requireCurrent, String binding, String sourceActor,
                             @Nullable Transform savedRoot) {
        public static Definition read(JsonObject data) {
            return new Definition(Mode.valueOf(data.get("mode").getAsString()), data.get("local_player_index").getAsInt(),
                    data.get("require_current").getAsBoolean(), data.get("binding").getAsString(),
                    data.get("source_actor").getAsString(), NiagaraObjectPropertyReader.savedSceneTransform(data, "source_root_transform"));
        }
    }

    public record Snapshot(boolean valid, Transform transform, Velocity velocity) {
        private static final Transform IDENTITY = new Transform(0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        private static final Velocity ZERO = new Velocity(0, 0, 0);
        public static final Snapshot EMPTY = new Snapshot(false, IDENTITY, ZERO);

        public int[] read(String function) {
            return switch (function) {
                case "GetMatrix" -> matrixWords();
                case "GetTransform" -> new int[]{success(), bits(transform.x()), bits(transform.y()), bits(transform.z()),
                        bits(transform.qx()), bits(transform.qy()), bits(transform.qz()), bits(transform.qw()),
                        bits(transform.scaleX()), bits(transform.scaleY()), bits(transform.scaleZ())};
                case "GetVelocity" -> new int[]{success(), bits(velocity.x()), bits(velocity.y()), bits(velocity.z())};
                default -> throw new IllegalArgumentException("Unknown Niagara Actor Component function: " + function);
            };
        }

        private int success() { return valid ? -1 : 0; }
        private int[] matrixWords() {
            var matrix = NiagaraComponentMatrices.srt(transform.qx(), transform.qy(), transform.qz(), transform.qw(),
                    transform.scaleX(), transform.scaleY(), transform.scaleZ(), transform.x(), transform.y(), transform.z(), new Matrix4d());
            int[] words = new int[17];
            words[0] = success();
            for (int index = 0; index < 16; index++) words[index + 1] = bits(matrix.get(index / 4, index % 4));
            return words;
        }
    }

    private final Definition definition;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public NiagaraActorComponent(Definition definition) { this.definition = definition; }
    public Definition definition() { return definition; }
    public Snapshot snapshot() { return snapshot; }

    public static Binding readSavedBinding(JsonObject data) {
        return switch (data.get("role").getAsString()) {
            case "actor" -> {
                var root = NiagaraObjectPropertyReader.savedSceneTransform(data, "root");
                yield new Actor(root == null ? null : new Component(root, root, Snapshot.ZERO, null));
            }
            case "component" -> new Component(NiagaraObjectPropertyReader.savedSceneTransform(data, "scene"),
                    NiagaraObjectPropertyReader.savedSceneTransform(data, "owner"), null, null);
            default -> throw new IllegalArgumentException("Invalid saved Niagara Actor Component binding role");
        };
    }

    public void capture(ActorComponentInputs inputs, @Nullable NiagaraParameterStore parameters,
                        IntFunction<@Nullable Component> localPlayers, Function<Object, @Nullable Binding> savedObjects,
                        double x, double y, double z) {
        var resolved = resolve(inputs, parameters, localPlayers, savedObjects);
        if (resolved == null && definition.savedRoot() != null) {
            snapshot = new Snapshot(true, relative(definition.savedRoot(), x, y, z), Snapshot.ZERO);
            return;
        }
        var component = resolved == null ? null : resolved.component();
        var transform = component == null ? null : component.sceneTransform() != null ? component.sceneTransform()
                : component.ownerPresent() ? component.ownerTransform() : null;
        if (transform == null) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        var velocity = component.ownerPresent() ? component.ownerVelocity() : component.physicsVelocity();
        snapshot = new Snapshot(true, relative(transform, x, y, z), velocity == null ? Snapshot.ZERO : velocity);
    }

    public ComponentTickGroup requiredTickGroup(ActorComponentInputs inputs, @Nullable NiagaraParameterStore parameters,
            IntFunction<@Nullable Component> localPlayers, Function<Object, @Nullable Binding> savedObjects) {
        if (!definition.requireCurrent()) return ComponentTickGroup.TG_PrePhysics;
        var resolved = resolve(inputs, parameters, localPlayers, savedObjects);
        if (resolved == null || !resolved.live() || resolved.component() == null) return ComponentTickGroup.TG_PrePhysics;
        var component = resolved.component();
        return ComponentTickGroup.afterPrerequisite(component.tickStart(), component.tickEnd());
    }

    private record ResolvedComponent(@Nullable Component component, boolean live) { }

    private @Nullable ResolvedComponent resolve(ActorComponentInputs inputs, @Nullable NiagaraParameterStore parameters,
            IntFunction<@Nullable Component> localPlayers, Function<Object, @Nullable Binding> savedObjects) {
        var component = switch (definition.mode()) {
            case Default -> null;
            case AttachParent -> inputs.attached();
            case LocalPlayer -> {
                var root = inputs.localPlayerRoots().get(definition.localPlayerIndex());
                yield root == null ? localPlayers.apply(definition.localPlayerIndex()) : root;
            }
        };
        if (component != null) return new ResolvedComponent(component, true);
        var resolution = resolveDefault(inputs, parameters, savedObjects);
        if (resolution == null) return null;
        return new ResolvedComponent(resolution.binding() instanceof Actor actor ? actor.root() : (Component) resolution.binding(),
                resolution.live());
    }

    private record Resolution(Binding binding, boolean live) { }

    private @Nullable Resolution resolveDefault(ActorComponentInputs inputs, @Nullable NiagaraParameterStore parameters,
                                                Function<Object, @Nullable Binding> savedObjects) {
        var binding = inputs.objects().get(definition.binding());
        if (binding == null && parameters != null && !definition.binding().isEmpty()) {
            var object = parameters.objectBinding(definition.binding()).value();
            String identity = object == null ? null : NiagaraParameterStoreSources.objectIdentity(object);
            if (identity != null) binding = inputs.objects().get(identity);
            if (binding == null && object != null) {
                var saved = savedObjects.apply(object);
                if (saved != null) return new Resolution(saved, false);
            }
        }
        if (binding != null) return new Resolution(binding, true);
        var sourceActor = inputs.objects().get(definition.sourceActor());
        return sourceActor instanceof Actor ? new Resolution(sourceActor, true) : null;
    }

    private static Transform relative(Transform transform, double x, double y, double z) {
        return new Transform(transform.x() - x, transform.y() - y, transform.z() - z,
                transform.qx(), transform.qy(), transform.qz(), transform.qw(),
                transform.scaleX(), transform.scaleY(), transform.scaleZ());
    }

    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
}
