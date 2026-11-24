package com.kltyton.lumenfx.api.client;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The contacts between one Unity emitter instance and one collider in a simulation frame. */
public record UnityParticleCollisionEvent(String emitter, Collider collider, List<Hit> hits) {
    public UnityParticleCollisionEvent { hits = List.copyOf(hits); }

    public enum Kind { BLOCK, ENTITY, PLANE }

    /** The key is a packed BlockPos, a level entity ID, or the authored plane slot. */
    public record Collider(Kind kind, long key) { }

    /** World-space contact point, outward surface normal, incident velocity in blocks per second, authored collider force vector, and particle diameter. */
    public record Hit(double time, Vec3 intersection, Vec3 normal, Vec3 incidentVelocity, Vec3 appliedForce,
                      double particleDiameter) {
        public Hit(double time, Vec3 intersection, Vec3 normal, Vec3 incidentVelocity) {
            this(time, intersection, normal, incidentVelocity, Vec3.ZERO, 1.0);
        }

        public Hit(double time, Vec3 intersection, Vec3 normal, Vec3 incidentVelocity, Vec3 appliedForce) {
            this(time, intersection, normal, incidentVelocity, appliedForce, 1.0);
        }
    }

    /** Host-dispatched force impulse event emitted when World colliderForce != 0 collides with a dynamic entity. */
    public record ForceImpact(
            @Nullable Identifier effectId,
            long effectSeed,
            long emitterSeed,
            String emitter,
            long entityId,
            int particleId,
            int hitSeq,
            double time,
            Vec3 position,
            Vec3 direction,
            Vec3 normal,
            double speed,
            double particleSize,
            Vec3 force
    ) {
        public ForceImpact(
                @Nullable Identifier effectId,
                long effectSeed,
                String emitter,
                long entityId,
                int particleId,
                int hitSeq,
                double time,
                Vec3 position,
                Vec3 direction,
                Vec3 normal,
                double speed,
                double particleSize,
                Vec3 force
        ) {
            this(effectId, effectSeed, effectSeed, emitter, entityId, particleId, hitSeq, time,
                    position, direction, normal, speed, particleSize, force);
        }

        public ForceImpact(String emitter, long entityId, double time, Vec3 position, Vec3 force) {
            this(null, 0L, 0L, emitter, entityId, 0, 0, time, position,
                    force.lengthSqr() > 1e-12 ? force.normalize() : new Vec3(0, 1, 0),
                    new Vec3(0, 1, 0), 0.0, 1.0, force);
        }

        public ForceImpact withEffect(@Nullable Identifier id, long seed) {
            return new ForceImpact(id, seed, emitterSeed == 0L ? seed : emitterSeed, emitter, entityId, particleId, hitSeq, time,
                    position, direction, normal, speed, particleSize, force);
        }
    }
}
