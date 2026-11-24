package com.kltyton.lumenfx.loader.neoforge;

import com.lowdragmc.photon.PhotonConfig;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Binds NeoForge's ModConfigSpec file to the loader-independent {@link PhotonConfig#INSTANCE}. */
public final class PhotonNeoForgeConfig {
    public static final ModConfigSpec CONFIG_SPEC;

    static {
        var pair = new ModConfigSpec.Builder().configure(PhotonNeoForgeConfig::new);
        var holder = pair.getLeft();
        CONFIG_SPEC = pair.getRight();
        PhotonConfig.INSTANCE.fxCompositeMode.bind(holder.fxCompositeMode);
        PhotonConfig.INSTANCE.collisionParallel.bind(holder.collisionParallel);
        PhotonConfig.INSTANCE.collisionSectionCacheLimit.bind(holder.collisionSectionCacheLimit);
        PhotonConfig.INSTANCE.lightShadowedLights.bind(holder.lightShadowedLights);
        PhotonConfig.INSTANCE.lightVoxelBudgetMs.bind(holder.lightVoxelBudgetMs);
    }

    private final ModConfigSpec.ConfigValue<FXCompositeMode> fxCompositeMode;
    private final ModConfigSpec.BooleanValue collisionParallel;
    private final ModConfigSpec.IntValue collisionSectionCacheLimit;
    private final ModConfigSpec.IntValue lightShadowedLights;
    private final ModConfigSpec.DoubleValue lightVoxelBudgetMs;

    private PhotonNeoForgeConfig(ModConfigSpec.Builder builder) {
        fxCompositeMode = builder.defineEnum("fx_composite_mode", FXCompositeMode.LATE,
                FXCompositeMode.VANILLA, FXCompositeMode.LATE);
        builder.push("collision");
        collisionParallel = builder.define("parallel", true);
        collisionSectionCacheLimit = builder.defineInRange("section_cache_limit", 4096, 64, 16384);
        builder.pop();
        builder.push("particle_lights");
        lightShadowedLights = builder.defineInRange("shadowed_lights", 8, 0, PhotonConfig.MAX_SHADOWED_LIGHTS);
        lightVoxelBudgetMs = builder.defineInRange("voxel_budget_ms", 2.0, 0.1, 20.0);
        builder.pop();
    }
}
