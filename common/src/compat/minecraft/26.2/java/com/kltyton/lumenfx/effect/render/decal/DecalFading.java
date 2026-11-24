package com.kltyton.lumenfx.effect.render.decal;

/** Frozen native proxy fading parameters, independent of Niagara's per-frame bound fade. */
public record DecalFading(float inverseOut, float normalizedOut, float inverseIn, float normalizedIn) {
    public static final DecalFading DEFAULT = new DecalFading(-1, 1, 1, 0);

    public DecalFading fadeOut(float spawnTime, float delay, float duration) {
        float inverse = duration > 0 ? 1f / duration : -1;
        float normalized = duration > 0 ? (spawnTime + delay + duration) * inverse : 1;
        return new DecalFading(inverse, normalized, inverseIn, normalizedIn);
    }

    public DecalFading fadeIn(float spawnTime, float delay, float duration) {
        float inverse = duration > 0 ? 1f / duration : 1;
        float normalized = duration > 0 ? (spawnTime + delay) * -inverse : 0;
        return new DecalFading(inverseOut, normalizedOut, inverse, normalized);
    }

    public float opacity(double worldSeconds) {
        if (worldSeconds == 0) return 1;
        double out = worldSeconds * -inverseOut + normalizedOut;
        double in = worldSeconds * inverseIn + normalizedIn;
        double value = out < in ? out : in;
        return (float)(value < 0 ? 0 : value < 1 ? value : 1);
    }
}
