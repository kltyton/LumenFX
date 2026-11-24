package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.simulation.ParticleState;
import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.unity.UnitySubEmitter;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Captured on the particle's simulation worker before the child emitter's execution group. */
public record UnitySubEmitterEvent(UnitySubEmitter link, double time, Pose pose, Vec3 velocity,
                                   @Nullable ParticleState particle, @Nullable Color color, @Nullable Vec3 size,
                                   @Nullable Vec3 rotation,
                                   double remainingLifetime, double systemAge) {
    public UnitySubEmitterEvent withPose(Pose next) {
        return new UnitySubEmitterEvent(link, time, next, velocity, particle, color, size, rotation, remainingLifetime, systemAge);
    }
}
