package com.kltyton.lumenfx.effect.render.fog;

import net.minecraft.world.phys.Vec3;

/** Exponential scene fog in authored density/falloff units and UE world centimetres. */
public record ExponentialHeightFog(Layer primary, Layer secondary, Vec3 inscattering, float maximumOpacity,
                                   float startCm, float endCm, float cutoffCm, Directional directional) implements FogSettings {

    public record Layer(float density, float falloff, float heightCm) {
        public Layer {
            if (!Float.isFinite(density) || !Float.isFinite(falloff) || !Float.isFinite(heightCm))
                throw new IllegalArgumentException("Invalid exponential fog layer");
        }
        public float sceneDensity() { return density / 1000.0f; }
        public float sceneFalloff() { return falloff / 1000.0f; }
    }

    public record Directional(Vec3 color, Vec3 direction, float exponent, float startCm) {
        public Directional {
            requireColor(color);
            if (direction == null || !Double.isFinite(direction.x) || !Double.isFinite(direction.y)
                    || !Double.isFinite(direction.z) || !Float.isFinite(exponent) || !Float.isFinite(startCm))
                throw new IllegalArgumentException("Invalid directional fog input");
        }
    }

    public ExponentialHeightFog {
        requireColor(inscattering);
        if (primary == null || secondary == null || directional == null || !Float.isFinite(maximumOpacity)
                || !Float.isFinite(startCm) || !Float.isFinite(endCm) || !Float.isFinite(cutoffCm))
            throw new IllegalArgumentException("Invalid exponential scene fog");
    }

    private static void requireColor(Vec3 color) {
        if (color == null || !Double.isFinite(color.x) || !Double.isFinite(color.y) || !Double.isFinite(color.z))
            throw new IllegalArgumentException("Invalid linear fog color");
    }
}
