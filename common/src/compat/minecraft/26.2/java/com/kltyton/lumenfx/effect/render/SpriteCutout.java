package com.kltyton.lumenfx.effect.render;

/** Saved per-frame UV polygons used for sprite positions and texture coordinates. */
public record SpriteCutout(int verticesPerFrame, float[] coordinates, boolean custom) {
    public SpriteCutout(int verticesPerFrame, float[] coordinates) { this(verticesPerFrame, coordinates, false); }

    public SpriteCutout {
        coordinates = coordinates.clone();
        if (verticesPerFrame != 4 && verticesPerFrame != 8 || coordinates.length == 0
                || coordinates.length % (verticesPerFrame * 2) != 0)
            throw new IllegalArgumentException("Invalid sprite cutout geometry");
        for (float value : coordinates)
            if (!Float.isFinite(value) || !custom && (value < 0 || value > 1))
                throw new IllegalArgumentException("Invalid sprite cutout UV");
    }

    @Override public float[] coordinates() { return coordinates.clone(); }
    public int frames() { return coordinates.length / (verticesPerFrame * 2); }
    public int indexCount() { return (verticesPerFrame - 2) * 3; }
}
