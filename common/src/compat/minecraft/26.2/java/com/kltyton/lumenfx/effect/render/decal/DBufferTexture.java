package com.kltyton.lumenfx.effect.render.decal;

import java.util.List;

/** Logical DBuffer texture dependencies carried by compiled surface materials. */
public enum DBufferTexture {
    BASE_COLOR(1, "LfxDBufferA"), WORLD_NORMAL(2, "LfxDBufferB"), MATERIAL(4, "LfxDBufferC");

    public static final List<DBufferTexture> ALL = List.of(BASE_COLOR, WORLD_NORMAL, MATERIAL);
    private final int mask;
    private final String sampler;

    DBufferTexture(int mask, String sampler) { this.mask = mask; this.sampler = sampler; }
    public int mask() { return mask; }
    public String sampler() { return sampler; }
}
