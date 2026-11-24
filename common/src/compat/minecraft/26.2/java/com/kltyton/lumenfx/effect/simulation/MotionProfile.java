package com.kltyton.lumenfx.effect.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.simulation.unity.ShurikenStepClock;
import com.kltyton.lumenfx.effect.simulation.unity.UnityVelocityLimiter;
import com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts;
import com.kltyton.lumenfx.effect.simulation.unity.UnityOrbitalMotion;
import com.kltyton.lumenfx.effect.simulation.unity.UnityNoiseMotion;
import com.kltyton.lumenfx.effect.simulation.unity.UnityForceFields;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.noise.FieldSampling;
import com.kltyton.lumenfx.effect.unity.UnitySpaceMotion;
import com.kltyton.lumenfx.effect.unity.UnitySpaceTransform;
import com.kltyton.lumenfx.effect.unity.UnityCollision;
import com.kltyton.lumenfx.effect.unity.UnityVelocityLimit;
import com.kltyton.lumenfx.effect.unity.UnitySpeedModules;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import java.util.function.BiConsumer;
import com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.Impact;

/**
 * Per-emitter motion flags and scratch owned by a single simulation worker.
 * ParticleState instances cache and share this profile; the scratch is never global.
 */
final class MotionProfile {
    final boolean usesMotion;
    final boolean vectorField;
    final boolean niagara;
    final @Nullable ShurikenStepClock shurikenClock;
    final boolean displacement;
    final ScalarFunction drag;
    final List<ScalarFunction> forceOverLife;
    final List<ScalarFunction> velocityOverLife;
    final @Nullable ScalarFunction currentInheritedVelocity;
    final @Nullable UnityVelocityLimiter velocityLimiter;
    final @Nullable UnityParticleContacts contacts;
    final ScalarFunction sizeOverLife;
    final List<ScalarFunction> sizeOverLifetimeAxes;
    final @Nullable UnitySpeedModules speedModules;
    final List<ScalarFunction> spaceVelocity;
    final List<ScalarFunction> spaceForce;
    final @Nullable UnitySpaceTransform velocityTransform;
    final @Nullable UnitySpaceTransform forceTransform;
    final boolean randomizedForce;
    final @Nullable ScalarFunction speedModifier;
    final @Nullable UnityOrbitalMotion orbitalMotion;
    final @Nullable UnityNoiseMotion unityNoise;
    final @Nullable UnityForceFields externalForces;
    final boolean simulationLocal;
    final Quaterniond worldToLocal = new Quaterniond();
    final Quaterniond resolvedSpaceRotation = new Quaterniond();
    final Vector3d spaceVector = new Vector3d();
    final Vec3 acceleration;
    final EmitterDefinition.Noise noise;
    final FieldSampling field;
    final Vec3 fieldOffset;
    final double[] fieldOutput = new double[3];
    final double[] sampledVelocity = new double[3];
    double ownerScale = 1;
    Vec3 emitterVelocity = Vec3.ZERO;
    @Nullable BiConsumer<ParticleState, Impact> collisionImpact;

    MotionProfile(EmitterDefinition definition) {
        this(definition, null, null, null, null, List.of(), null, null, null, null);
    }

    MotionProfile(EmitterDefinition definition, @Nullable ScalarFunction currentInheritedVelocity,
                  @Nullable UnityVelocityLimit velocityLimit, @Nullable UnitySpaceMotion spaceMotion,
                  @Nullable UnityCollision collision, List<ScalarFunction> sizeOverLifetimeAxes,
                  @Nullable UnitySpeedModules speedModules, @Nullable UnityNoiseMotion unityNoise,
                  @Nullable UnityForceFields externalForces, @Nullable Double maximumParticleDeltaTime) {
        drag = definition.drag();
        forceOverLife = definition.forceOverLife();
        velocityOverLife = definition.velocityOverLife();
        this.currentInheritedVelocity = currentInheritedVelocity;
        velocityLimiter = velocityLimit == null ? null : new UnityVelocityLimiter(velocityLimit, definition.localSpace());
        sizeOverLife = definition.sizeOverLife();
        this.sizeOverLifetimeAxes = sizeOverLifetimeAxes;
        this.speedModules = speedModules;
        this.unityNoise = unityNoise;
        this.externalForces = externalForces;
        simulationLocal = definition.localSpace();
        contacts = collision == null ? null : new UnityParticleContacts(collision, simulationLocal);
        spaceVelocity = spaceMotion == null ? List.of() : spaceMotion.velocity();
        spaceForce = spaceMotion == null ? List.of() : spaceMotion.force();
        velocityTransform = spaceMotion == null ? null : spaceMotion.velocityTransform();
        forceTransform = spaceMotion == null ? null : spaceMotion.forceTransform();
        randomizedForce = spaceMotion != null && spaceMotion.randomizedForce();
        speedModifier = spaceMotion == null ? null : spaceMotion.speedModifier();
        orbitalMotion = spaceMotion == null || spaceMotion.orbit() == null ? null
                : new UnityOrbitalMotion(spaceMotion.orbit(), simulationLocal);
        acceleration = definition.acceleration();
        noise = definition.noise();
        boolean dragInvariant = drag.timeInvariant();
        boolean forcesInvariant = true;
        for (int i = 0; i < forceOverLife.size(); i++) {
            if (!forceOverLife.get(i).timeInvariant()) {
                forcesInvariant = false;
                break;
            }
        }
        vectorField = noise.mode() == EmitterDefinition.NoiseMode.VECTOR_FIELD && noise.strength().lengthSqr() != 0;
        niagara = definition.integration() == EmitterDefinition.Integration.NIAGARA;
        shurikenClock = maximumParticleDeltaTime == null ? null : new ShurikenStepClock(maximumParticleDeltaTime);
        field = noise.field();
        fieldOffset = field == null ? Vec3.ZERO : field.offsetScale().scale(NiagaraRandom.unit(field.seedX(), field.seedY(), field.seedZ()));
        displacement = noise.mode() == EmitterDefinition.NoiseMode.DISPLACEMENT && noise.strength().lengthSqr() != 0;
        usesMotion = niagara || shurikenClock != null || !dragInvariant || !forcesInvariant || randomizedForce || vectorField
                || currentInheritedVelocity != null || velocityLimiter != null || speedModifier != null
                || !spaceVelocity.isEmpty() || !spaceForce.isEmpty() || contacts != null || orbitalMotion != null
                || unityNoise != null || externalForces != null;
    }

    void pose(Pose pose) {
        ownerScale = pose.scale();
        if (velocityLimiter != null) velocityLimiter.pose(pose);
        if (orbitalMotion != null) orbitalMotion.pose(pose);
        boolean needsWorldToLocal = simulationLocal
                && ((!spaceVelocity.isEmpty() && (velocityTransform == null || !velocityTransform.fixed()))
                || (!spaceForce.isEmpty() && (forceTransform == null || !forceTransform.fixed())));
        if (needsWorldToLocal)
            worldToLocal.set(pose.x(), pose.y(), pose.z(), pose.w()).normalize().conjugate();
    }
}
