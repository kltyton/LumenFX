package com.kltyton.lumenfx.effect.render;

import com.kltyton.lumenfx.effect.MaterialDefinition;

/** Source pixel-coverage compensation channels and blend. */
public record SpriteCoverage(int channels, float blend, boolean enabled, boolean automatic) {
    public SpriteCoverage(int channels, float blend, boolean enabled) { this(channels, blend, enabled, false); }
    public SpriteCoverage(int channels, float blend) { this(channels, blend, channels != 0); }
    public static final SpriteCoverage DISABLED = new SpriteCoverage(0, 0, false);

    public SpriteCoverage {
        if (channels < 0 || channels > 15 || !Float.isFinite(blend) || blend < 0 || blend > 1)
            throw new IllegalArgumentException("Invalid sprite pixel coverage");
    }

    public SpriteCoverage forMaterial(MaterialDefinition material) {
        if (!automatic) return this;
        int resolved = material.automaticSpriteCoverageChannels();
        if (resolved == 0) return DISABLED;
        return enabled && resolved == channels ? this : new SpriteCoverage(resolved, blend);
    }

    public static int legacyAutomaticChannels(MaterialDefinition.Blend blend) {
        return switch (blend) {
            case ALPHA -> 8;
            case ADDITIVE, ADD -> 15;
            default -> 0;
        };
    }
}
