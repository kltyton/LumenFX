package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.niagara.interfaces.physics.NiagaraPhysicsAsset.Kind;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Read-only scene geometry and overlap queries for a Niagara simulation snapshot. */
public record NiagaraRigidMeshInputs(Map<String, Actor> actors, OverlapQuery overlap,
                                     Map<String, List<String>> sourceActors) {
    public static final NiagaraRigidMeshInputs EMPTY = new NiagaraRigidMeshInputs(Map.of(), ignored -> List.of());

    public NiagaraRigidMeshInputs(Map<String, Actor> actors, OverlapQuery overlap) { this(actors, overlap, Map.of()); }

    public NiagaraRigidMeshInputs {
        var names = new TreeMap<String, Actor>(SourceNames.ASCII_CASE_INSENSITIVE);
        names.putAll(Map.copyOf(actors));
        if (names.size() != actors.size()) throw new IllegalArgumentException("Duplicate Niagara rigid mesh actor identity");
        actors = Collections.unmodifiableMap(names);
        var overrides = new TreeMap<String, List<String>>(SourceNames.ASCII_CASE_INSENSITIVE);
        sourceActors.forEach((name, values) -> overrides.put(name, List.copyOf(values)));
        if (overrides.size() != sourceActors.size()) throw new IllegalArgumentException("Duplicate Niagara rigid mesh override");
        sourceActors = Collections.unmodifiableMap(overrides);
        Objects.requireNonNull(overlap);
    }

    @FunctionalInterface
    public interface OverlapQuery {
        /** Uses immutable scene data. The VM may call this concurrently on simulation workers. */
        List<String> actors(Overlap request);
    }

    /** Absolute source centimeters, source quaternion, and half size. */
    public record Overlap(Vec3 origin, float qx, float qy, float qz, float qw, Vec3 halfExtent,
                          int channel, boolean byObjectType, boolean complex) { }

    public record Actor(long order, boolean movable, List<String> tags, List<Component> components) {
        public Actor { tags = List.copyOf(tags); components = List.copyOf(components); }
    }

    public enum MeshKind { STATIC, SKELETAL }

    /** Stable component and geometry identities are used to preserve previous poses across actor discovery. */
    public record Component(long order, Object identity, Object geometryIdentity, MeshKind kind, List<String> tags,
                            Transform world, Bounds localBounds, List<Body> bodies, @Nullable DistanceField distanceField) {
        public Component {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(geometryIdentity);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(world);
            Objects.requireNonNull(localBounds);
            tags = List.copyOf(tags);
            bodies = List.copyOf(bodies);
        }
    }

    /** Current bone/component world pose. An empty authored shape list uses the source body bounds fallback. */
    public record Body(Transform world, List<Shape> shapes) {
        public Body { Objects.requireNonNull(world); shapes = List.copyOf(shapes); }
    }

    public enum Collision { NONE, QUERY, PHYSICS, QUERY_AND_PHYSICS, PROBE, QUERY_AND_PROBE }

    /** Convex primitives are supplied as their authored local bounding boxes, ahead of box elements. */
    public record Shape(Kind kind, Vec3 center, Vec3 pitchYawRoll, Vec3 extent, Collision collision) {
        public Shape {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(collision);
            for (Vec3 value : List.of(center, pitchYawRoll, extent))
                if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
                    throw new IllegalArgumentException("Non-finite Niagara rigid mesh primitive");
        }
    }

    /** Scene distance-field identity from the corresponding live static mesh reference. */
    public record DistanceField(int index, int acquireTag) { }
}
