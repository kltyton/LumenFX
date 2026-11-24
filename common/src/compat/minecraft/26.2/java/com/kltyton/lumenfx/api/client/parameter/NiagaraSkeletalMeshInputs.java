package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.component.ComponentTickWindow;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.interfaces.physics.NiagaraPhysicsAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.SkinWeightBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Live skeletal component publications for an effect's source-resolution modes. */
public record NiagaraSkeletalMeshInputs(Map<String, Component> components, Map<String, List<Component>> actors,
                                       Map<String, Component> parameters, List<Component> attachments, List<Component> children,
                                       Map<String, PhysicsCollector> physicsCollectors, SourceHierarchy hierarchy) {
    public static final NiagaraSkeletalMeshInputs EMPTY =
            new NiagaraSkeletalMeshInputs(Map.of(), Map.of(), Map.of(), List.of(), List.of());

    public NiagaraSkeletalMeshInputs(Map<String, Component> components, Map<String, List<Component>> actors,
                                     Map<String, Component> parameters, List<Component> attachments, List<Component> children) {
        this(components, actors, parameters, attachments, children, Map.of());
    }

    public NiagaraSkeletalMeshInputs(Map<String, Component> components, Map<String, List<Component>> actors,
                                     Map<String, Component> parameters, List<Component> attachments, List<Component> children,
                                     Map<String, PhysicsCollector> physicsCollectors) {
        this(components, actors, parameters, attachments, children, physicsCollectors, SourceHierarchy.EMPTY);
    }

    public NiagaraSkeletalMeshInputs {
        components = names(components);
        var actorCopy = new TreeMap<String, List<Component>>(SourceNames.ASCII_CASE_INSENSITIVE);
        actors.forEach((name, matches) -> actorCopy.put(name, List.copyOf(matches)));
        if (actorCopy.size() != actors.size()) throw new IllegalArgumentException("Duplicate Niagara skeletal actor name");
        actors = Collections.unmodifiableMap(actorCopy);
        parameters = names(parameters);
        attachments = List.copyOf(attachments); children = List.copyOf(children);
        physicsCollectors = names(physicsCollectors);
    }

    /** Attachments and outers are nearest first, and each actor's list begins with its scene root. */
    public record SourceHierarchy(List<Component> outers, List<String> attachmentRootActors,
                                  Map<String, Component> skeletalActorComponents, Map<String, String> actorParameters) {
        private static final SourceHierarchy EMPTY = new SourceHierarchy(List.of(), List.of(), Map.of(), Map.of());

        public SourceHierarchy {
            outers = List.copyOf(outers);
            attachmentRootActors = List.copyOf(attachmentRootActors);
            skeletalActorComponents = names(skeletalActorComponents);
            actorParameters = names(actorParameters);
        }
    }

    /**
     * Bone poses are in mesh component space and the transform uses absolute source centimeters.
     * The first available LOD excludes both nonresident LODs and LODs pending eviction.
     */
    public record Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                            List<String> componentClasses, List<String> tags, int firstResidentLod,
                            @Nullable DeformedGeometry deformation, @Nullable Object componentIdentity,
                            @Nullable NiagaraPhysicsAsset physicsAsset, Map<Integer, SkinWeightOverride> skinWeightOverrides,
                            ComponentTickWindow tickWindow, boolean registered) {
        public Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                         List<String> componentClasses, List<String> tags, int firstResidentLod,
                         @Nullable DeformedGeometry deformation, @Nullable Object componentIdentity,
                         @Nullable NiagaraPhysicsAsset physicsAsset, Map<Integer, SkinWeightOverride> skinWeightOverrides,
                         ComponentTickWindow tickWindow) {
            this(mesh, transform, componentPose, componentClasses, tags, firstResidentLod, deformation, componentIdentity,
                    physicsAsset, skinWeightOverrides, tickWindow, true);
        }
        public Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                         List<String> componentClasses, List<String> tags, int firstResidentLod,
                         @Nullable DeformedGeometry deformation, @Nullable Object componentIdentity,
                         @Nullable NiagaraPhysicsAsset physicsAsset, Map<Integer, SkinWeightOverride> skinWeightOverrides) {
            this(mesh, transform, componentPose, componentClasses, tags, firstResidentLod, deformation,
                    componentIdentity, physicsAsset, skinWeightOverrides, ComponentTickWindow.DEFAULT);
        }

        public Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                         List<String> componentClasses, List<String> tags, int firstResidentLod,
                         @Nullable DeformedGeometry deformation, @Nullable Object componentIdentity,
                         @Nullable NiagaraPhysicsAsset physicsAsset) {
            this(mesh, transform, componentPose, componentClasses, tags, firstResidentLod, deformation,
                    componentIdentity, physicsAsset, Map.of());
        }
        public Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                         List<String> componentClasses, List<String> tags, int firstResidentLod,
                         @Nullable DeformedGeometry deformation, @Nullable Object componentIdentity) {
            this(mesh, transform, componentPose, componentClasses, tags, firstResidentLod, deformation, componentIdentity, null);
        }
        public Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                         List<String> componentClasses, List<String> tags, int firstResidentLod, @Nullable DeformedGeometry deformation) {
            this(mesh, transform, componentPose, componentClasses, tags, firstResidentLod, deformation, null);
        }
        public Component {
            Objects.requireNonNull(tickWindow);
            componentPose = List.copyOf(componentPose);
            componentClasses = List.copyOf(componentClasses); tags = List.copyOf(tags);
            skinWeightOverrides = Map.copyOf(skinWeightOverrides);
            if (firstResidentLod < 0 || !componentPose.isEmpty() && (mesh == null || componentPose.size() != mesh.bones().size()))
                throw new IllegalArgumentException("Skeletal component pose does not match its source mesh");
            if (deformation != null && (mesh == null || deformation.lod() >= mesh.lods().size()
                    || deformation.vertices() != mesh.lods().get(deformation.lod()).vertexCount()))
                throw new IllegalArgumentException("Deformed skeletal geometry does not match its source LOD");
            for (var entry : skinWeightOverrides.entrySet()) {
                int lod = entry.getKey();
                if (mesh == null || lod < 0 || lod >= mesh.lods().size()
                        || mesh.lods().get(lod) != entry.getValue().lod() && entry.getValue().lod().index() != lod)
                    throw new IllegalArgumentException("Skeletal skin weight override does not match its source LOD");
            }
        }
        public Component(@Nullable SkeletalMeshAsset mesh, @Nullable Transform transform, List<LocalTransform> componentPose,
                         List<String> componentClasses, List<String> tags, int firstResidentLod) {
            this(mesh, transform, componentPose, componentClasses, tags, firstResidentLod, null);
        }
        public static Component asset(SkeletalMeshAsset mesh) {
            return new Component(mesh, null, List.of(), List.of(), List.of(), 0);
        }

        public SkinWeightBuffer activeSkinWeights(int lod) {
            var override = skinWeightOverrides.get(lod);
            if (override != null) return override.buffer();
            if (mesh == null) throw new IllegalStateException("The skeletal component has no mesh");
            return java.util.Objects.requireNonNull(mesh.lods().get(lod).skinWeightBuffer(),
                    "The requested skeletal LOD is not resident");
        }

        public SkinWeightBuffer activeSkinWeights(int lod, Lod residentLod) {
            var override = skinWeightOverrides.get(lod);
            if (override == null) return java.util.Objects.requireNonNull(residentLod.skinWeightBuffer(),
                    "The requested skeletal LOD is not resident");
            residentLod.validateWeights(override.buffer());
            return override.buffer();
        }
    }

    /** The active component override or skin weight profile for one specific render LOD. */
    public record SkinWeightOverride(Lod lod, SkinWeightBuffer buffer) {
        public SkinWeightOverride { lod.validateWeights(buffer); }
    }

    /** Result of collecting attachment-provided collision sources in host hierarchy order. */
    public record PhysicsCollector(@Nullable Transform frame, List<PhysicsSource> sources, @Nullable NiagaraPhysicsAsset primary) {
        public PhysicsCollector { sources = List.copyOf(sources); }
    }

    public record PhysicsSource(@Nullable Component component, NiagaraPhysicsAsset asset) { }

    /** Component-space positions and signed tangent X/Z pairs from the render mesh for one LOD. */
    public static final class DeformedGeometry {
        private final int lod, vertices;
        private final float @Nullable [] positions, previousPositions, tangents;

        public DeformedGeometry(int lod, int vertices, float @Nullable [] positions, float @Nullable [] previousPositions,
                                float @Nullable [] tangents) {
            if (lod < 0 || vertices < 1 || positions == null && tangents == null || positions == null && previousPositions != null)
                throw new IllegalArgumentException("Invalid deformed skeletal channels");
            this.lod = lod;
            this.vertices = vertices;
            this.positions = channel(positions, Math.multiplyExact(vertices, 3));
            this.previousPositions = previousPositions == null ? this.positions : channel(previousPositions, Math.multiplyExact(vertices, 3));
            this.tangents = channel(tangents, Math.multiplyExact(vertices, 8));
        }

        public int lod() { return lod; }
        public int vertices() { return vertices; }
        public boolean hasPositions() { return positions != null; }
        public boolean hasTangents() { return tangents != null; }
        public float position(int index, boolean previous) {
            var values = previous ? previousPositions : positions;
            if (values == null) throw new IllegalStateException("Deformed skeletal positions are absent");
            return values[index];
        }
        public float tangent(int index) {
            if (tangents == null) throw new IllegalStateException("Deformed skeletal tangents are absent");
            return tangents[index];
        }

        private static float @Nullable [] channel(float @Nullable [] values, int length) {
            if (values == null) return null;
            if (values.length != length) throw new IllegalArgumentException("Deformed skeletal channel has the wrong vertex count");
            var copy = values.clone();
            for (float value : copy) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite deformed skeletal channel");
            return copy;
        }
    }

    private static <T> Map<String, T> names(Map<String, T> source) {
        var result = new TreeMap<String, T>(SourceNames.ASCII_CASE_INSENSITIVE);
        result.putAll(Map.copyOf(source));
        if (result.size() != source.size()) throw new IllegalArgumentException("Duplicate Niagara skeletal source name");
        return Collections.unmodifiableMap(result);
    }
}
