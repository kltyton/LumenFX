package com.kltyton.lumenfx.api.server;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.common.force.ServerColliderForceAuthority;
import com.kltyton.lumenfx.common.force.ServerColliderForceSpecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Public server entry point for registering authored collider-force contracts and authorizing active effect
 * instances so particle-entity pushes are validated and computed authoritatively on the server.
 */
public final class LumenFxServerEffects {
    private LumenFxServerEffects() { }

    /**
     * Immutable host inputs for the server's particle step. Velocity is in blocks per second.
     * Force fields are keyed by the exported source ID and use the same root-relative basis as the client.
     */
    public record CollisionInput(Pose pose, @Nullable Vec3 velocity, UnityCollisionLayers collisionLayers,
                                 Map<Identifier, StaticMeshGeometry> emissionGeometry,
                                 Map<String, List<UnityTrigger.Collider>> triggerColliders,
                                 Map<String, Integer> manualRequests, boolean emitting, boolean stopping,
                                 Map<String, FieldState> forceFields) {
        public CollisionInput(Pose pose, @Nullable Vec3 velocity) {
            this(pose, velocity, UnityCollisionLayers.EMPTY, Map.of(), Map.of(), Map.of(), true, false);
        }

        public CollisionInput(Pose pose, @Nullable Vec3 velocity, UnityCollisionLayers collisionLayers,
                              Map<Identifier, StaticMeshGeometry> emissionGeometry,
                              Map<String, List<UnityTrigger.Collider>> triggerColliders,
                              Map<String, Integer> manualRequests, boolean emitting, boolean stopping) {
            this(pose, velocity, collisionLayers, emissionGeometry, triggerColliders, manualRequests, emitting, stopping,
                    Map.of());
        }

        public CollisionInput {
            Objects.requireNonNull(pose);
            Objects.requireNonNull(collisionLayers);
            if (velocity != null && !Double.isFinite(velocity.lengthSqr()))
                throw new IllegalArgumentException("Particle host velocity must be finite");
            emissionGeometry = Map.copyOf(emissionGeometry);
            Map<String, List<UnityTrigger.Collider>> snapshot = new LinkedHashMap<>();
            triggerColliders.forEach((name, colliders) -> snapshot.put(name, List.copyOf(colliders)));
            triggerColliders = Map.copyOf(snapshot);
            manualRequests = Map.copyOf(manualRequests);
            forceFields = com.kltyton.lumenfx.effect.unity.UnityExternalForces.copyFieldStates(forceFields);
        }
    }

    /** Registers or overrides the trusted server-side spec for an authored emitter's colliderForce parameters. */
    public static void registerColliderSpec(Identifier effectId, String emitterName, double colliderForce,
                                            boolean multiplyByAngle, boolean multiplyBySpeed, boolean multiplyBySize,
                                            double maxSpeed, double maxSize) {
        ServerColliderForceSpecs.register(effectId, emitterName, new ServerColliderForceSpecs.EmitterForceSpec(
                colliderForce, multiplyByAngle, multiplyBySpeed, multiplyBySize, maxSpeed, maxSize));
    }

    /** Registers emitter colliderForce specs directly from an effect definition JSON object on the server. */
    public static void registerColliderSpecsFromJson(Identifier effectId, JsonObject effectJson) {
        ServerColliderForceSpecs.registerFromJson(effectId, effectJson);
    }

    /** Registers an authored effect and prepares all its collision assets from server-provided resources. */
    public static void registerColliderSpecsFromJson(Identifier effectId, JsonObject effectJson, ResourceProvider resources) {
        ServerColliderForceAuthority.registerDefinition(effectId, effectJson, resources);
    }

    /** Authorizes a world-anchored effect instance (with shared client seed) to push entities within {@code maxRange}. */
    public static Runnable authorizeInstance(ServerLevel level, Identifier effectId, long seed, Vec3 origin,
                                             double maxRange, int durationTicks) {
        return ServerColliderForceAuthority.authorize(level, effectId, seed, origin, maxRange, durationTicks);
    }

    /** Starts a server-authored simulation with sampled attachment, collisions, triggers, and force fields. */
    public static Runnable authorizeInstance(ServerLevel level, Identifier effectId, long seed,
                                             Supplier<@Nullable CollisionInput> input, double maxRange, int durationTicks) {
        return ServerColliderForceAuthority.authorize(level, effectId, seed, input, maxRange, durationTicks);
    }

    /** Authorizes an entity-attached effect instance (with shared client seed) to push entities within {@code maxRange}. */
    public static Runnable authorizeEntityInstance(ServerLevel level, Identifier effectId, long seed, Entity owner,
                                                   double maxRange, int durationTicks) {
        return ServerColliderForceAuthority.authorizeEntity(level, effectId, seed, owner, maxRange, durationTicks);
    }
}
