package com.kltyton.lumenfx.api.server;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.runtime.simulation.ServerForceAuthority;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Public server entry point for collider-force contracts on Minecraft 1.20.1 and 1.21.1. */
public final class LumenFxServerEffects {
    private LumenFxServerEffects() { }

    public static void registerColliderSpec(ResourceLocation effectId, String emitterName, double colliderForce,
                                            boolean multiplyByAngle, boolean multiplyBySpeed, boolean multiplyBySize,
                                            double maxSpeed, double maxSize) {
        ServerForceAuthority.registerSpec(effectId.toString(), emitterName, new ServerForceAuthority.ForceSpec(
                colliderForce, multiplyByAngle, multiplyBySpeed, multiplyBySize, maxSpeed, maxSize));
    }

    public static void registerColliderSpecsFromJson(ResourceLocation effectId, JsonObject effectJson) {
        ServerForceAuthority.registerFromJson(effectId.toString(), effectJson);
    }

    public static Runnable authorizeInstance(ServerLevel level, ResourceLocation effectId, long seed, Vec3 origin,
                                             double maxRange, int durationTicks) {
        return ServerForceAuthority.authorize(effectId.toString(), seed, () -> origin, maxRange, durationTicks);
    }

    public static Runnable authorizeEntityInstance(ServerLevel level, ResourceLocation effectId, long seed, Entity owner,
                                                   double maxRange, int durationTicks) {
        return ServerForceAuthority.authorize(effectId.toString(), seed,
                () -> owner.isRemoved() ? null : owner.position(), maxRange, durationTicks);
    }
}
