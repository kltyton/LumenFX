package com.kltyton.lumenfx.api.client.physics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Immutable source-space publications from a world's Chaos event producers. Vectors use UE axes and centimeters. */
public record NiagaraChaosScene(@Nullable Solver worldSolver, Map<String, Solver> actorSolvers) {
    public static final NiagaraChaosScene EMPTY = new NiagaraChaosScene(null, Map.of());

    public NiagaraChaosScene {
        actorSolvers = Map.copyOf(actorSolvers);
    }

    public enum EventKind { COLLISION, BREAKING, TRAILING }

    /** A null solver actor identifies the world's default solver. */
    public record EventDemand(@Nullable String solverActor, EventKind kind) {
        public EventDemand { Objects.requireNonNull(kind); }
    }

    public record Body(Vec3 velocity, Vec3 angularVelocity, float mass, int surfaceType,
                       @Nullable String physicalMaterial) {
        public Body {
            Objects.requireNonNull(velocity);
            Objects.requireNonNull(angularVelocity);
        }
    }

    public record Bounds(Vec3 minimum, Vec3 maximum) {
        public Bounds {
            Objects.requireNonNull(minimum);
            Objects.requireNonNull(maximum);
        }
    }

    public record Collision(Vec3 location, Vec3 impulse, Vec3 normal, Body first, Body second) {
        public Collision {
            Objects.requireNonNull(location);
            Objects.requireNonNull(impulse);
            Objects.requireNonNull(normal);
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
        }
    }

    public record Breaking(Vec3 location, Body body, Bounds bounds) {
        public Breaking {
            Objects.requireNonNull(location);
            Objects.requireNonNull(body);
            Objects.requireNonNull(bounds);
        }
    }

    public record Trailing(Vec3 location, Body body, Bounds bounds, boolean notifyTrailing) {
        public Trailing {
            Objects.requireNonNull(location);
            Objects.requireNonNull(body);
            Objects.requireNonNull(bounds);
        }
    }

    /** Each delivered callback has a strictly increasing sequence across the solvers in this world. */
    public record Batch<E>(long sequence, List<E> entries) {
        public Batch { entries = List.copyOf(entries); }
    }

    /** A null batch indicates that this solver has yet to deliver a callback of that kind. */
    public record Solver(float time, boolean collisionsEnabled, boolean breakingsEnabled, boolean trailingsEnabled,
                         @Nullable Batch<Collision> collisions, @Nullable Batch<Breaking> breakings,
                         @Nullable Batch<Trailing> trailings) {
        public Solver {
            if (!Float.isFinite(time)) throw new IllegalArgumentException("Non-finite Chaos solver time");
        }
    }
}
