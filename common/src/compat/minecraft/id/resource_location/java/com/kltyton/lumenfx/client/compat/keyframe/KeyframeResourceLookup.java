package com.kltyton.lumenfx.client.compat.keyframe;

import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Version-specific Minecraft ID and vanilla particle registry helper for MC 1.20.1 / 1.21.1 ({@link ResourceLocation}).
 */
public final class KeyframeResourceLookup {
    private KeyframeResourceLookup() { }

    public static @Nullable Object tryParseId(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : ResourceLocation.tryParse(trimmed);
    }

    public static boolean hasVanillaParticleType(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            ResourceLocation id = ResourceLocation.tryParse(raw.trim());
            return id != null && BuiltInRegistries.PARTICLE_TYPE.containsKey(id);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
