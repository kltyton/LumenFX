package com.kltyton.lumenfx.internal.photon.core;

import com.kltyton.lumenfx.platform.LumenPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.file.Path;

/** Embedded minimal LDLib2 Platform shim for loaders without external LDLib2. */
public final class Platform {
    private static final RegistryAccess FROZEN_FALLBACK =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private Platform() { }

    public static Path getGamePath() {
        return LumenPlatform.gameDir();
    }

    public static boolean isModLoaded(String modId) {
        return LumenPlatform.isModLoaded(modId);
    }

    public static RegistryAccess getFrozenRegistry() {
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                return mc.level.registryAccess();
            }
            if (mc != null && mc.getConnection() != null) {
                return mc.getConnection().registryAccess();
            }
        } catch (Throwable ignored) {
        }
        return FROZEN_FALLBACK;
    }
}
