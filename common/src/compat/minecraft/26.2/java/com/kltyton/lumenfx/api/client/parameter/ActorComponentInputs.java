package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Host actor and component state published for Niagara's Actor Component data interface. */
public record ActorComponentInputs(Map<String, Binding> objects, @Nullable Component attached,
                                   Map<Integer, Component> localPlayerRoots) {
    public static final ActorComponentInputs EMPTY = new ActorComponentInputs(Map.of(), null, Map.of());

    public ActorComponentInputs {
        var named = new TreeMap<String, Binding>(SourceNames.ASCII_CASE_INSENSITIVE);
        objects.forEach((name, binding) -> {
            if (name.isBlank() || named.putIfAbsent(name, Objects.requireNonNull(binding)) != null)
                throw new IllegalArgumentException("Invalid or duplicate Niagara Actor Component object name: " + name);
        });
        objects = Collections.unmodifiableMap(named);
        localPlayerRoots = Map.copyOf(localPlayerRoots);
    }

    public sealed interface Binding permits Actor, Component { }
    public record Actor(@Nullable Component root) implements Binding { }
    /** Velocity uses source axes and centimeters per second. */
    public record Velocity(double x, double y, double z) { }
    public record Component(@Nullable Transform sceneTransform, @Nullable Transform ownerTransform,
                            @Nullable Velocity ownerVelocity, @Nullable Velocity physicsVelocity, boolean ownerPresent,
                            ComponentTickGroup tickStart, ComponentTickGroup tickEnd,
                            @Nullable NiagaraBounds bounds, List<String> gameplayTags,
                            @Nullable String componentType, List<String> componentSuperclasses,
                            boolean blendsSkeletalPhysics) implements Binding {
        public Component(@Nullable Transform sceneTransform, @Nullable Transform ownerTransform,
                         @Nullable Velocity ownerVelocity, @Nullable Velocity physicsVelocity, boolean ownerPresent,
                         ComponentTickGroup tickStart, ComponentTickGroup tickEnd,
                         @Nullable NiagaraBounds bounds, List<String> gameplayTags,
                         @Nullable String componentType, List<String> componentSuperclasses) {
            this(sceneTransform, ownerTransform, ownerVelocity, physicsVelocity, ownerPresent, tickStart, tickEnd,
                    bounds, gameplayTags, componentType, componentSuperclasses, false);
        }

        public Component(@Nullable Transform sceneTransform, @Nullable Transform ownerTransform,
                         @Nullable Velocity ownerVelocity, @Nullable Velocity physicsVelocity, boolean ownerPresent,
                         ComponentTickGroup tickStart, ComponentTickGroup tickEnd,
                         @Nullable NiagaraBounds bounds, List<String> gameplayTags,
                         @Nullable String componentType) {
            this(sceneTransform, ownerTransform, ownerVelocity, physicsVelocity, ownerPresent, tickStart, tickEnd,
                    bounds, gameplayTags, componentType, List.of());
        }

        public Component(@Nullable Transform sceneTransform, @Nullable Transform ownerTransform,
                         @Nullable Velocity ownerVelocity, @Nullable Velocity physicsVelocity, boolean ownerPresent,
                         ComponentTickGroup tickStart, ComponentTickGroup tickEnd) {
            this(sceneTransform, ownerTransform, ownerVelocity, physicsVelocity, ownerPresent, tickStart, tickEnd,
                    null, List.of(), null, List.of());
        }

        public Component(@Nullable Transform sceneTransform, @Nullable Transform ownerTransform,
                         @Nullable Velocity ownerVelocity, @Nullable Velocity physicsVelocity) {
            this(sceneTransform, ownerTransform, ownerVelocity, physicsVelocity, ownerTransform != null || ownerVelocity != null,
                    ComponentTickGroup.TG_EndPhysics, ComponentTickGroup.TG_EndPhysics, null, List.of(), null, List.of());
        }

        public Component {
            Objects.requireNonNull(tickStart);
            Objects.requireNonNull(tickEnd);
            gameplayTags = gameplayTags == null ? List.of() : List.copyOf(gameplayTags);
            componentSuperclasses = componentSuperclasses == null ? List.of() : List.copyOf(componentSuperclasses);
        }
    }
}
