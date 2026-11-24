package com.kltyton.lumenfx.effect.render.material;

/** Shared sampler selected by a native material sample expression. */
public enum MaterialSamplerSource {
    WORLD_WRAP(false, false),
    WORLD_CLAMP(true, false),
    TERRAIN_WEIGHTMAP(true, true),
    VIEW_WORLD_WRAP(false, true),
    VIEW_WORLD_CLAMP(true, true);

    private final boolean clamp;
    private final boolean viewBias;

    MaterialSamplerSource(boolean clamp, boolean viewBias) {
        this.clamp = clamp;
        this.viewBias = viewBias;
    }

    public boolean clamps() { return clamp; }
    public boolean usesViewBias() { return viewBias; }

    public static MaterialSamplerSource parse(String value) {
        return switch (value) {
            case "WorldWrap" -> WORLD_WRAP;
            case "WorldClamp" -> WORLD_CLAMP;
            case "TerrainWeightmap" -> TERRAIN_WEIGHTMAP;
            case "ViewWorldWrap" -> VIEW_WORLD_WRAP;
            case "ViewWorldClamp" -> VIEW_WORLD_CLAMP;
            default -> throw new IllegalArgumentException("Unknown material sampler source " + value);
        };
    }
}
