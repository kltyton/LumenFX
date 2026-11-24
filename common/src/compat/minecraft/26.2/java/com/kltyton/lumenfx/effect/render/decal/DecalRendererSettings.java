package com.kltyton.lumenfx.effect.render.decal;

public record DecalRendererSettings(float screenSizeFade, @org.jspecify.annotations.Nullable String materialBinding,
                                   @org.jspecify.annotations.Nullable String invalidDomainMaterial, String materialVariant) {
    public static final DecalRendererSettings DEFAULT = new DecalRendererSettings(0);
    public DecalRendererSettings(float screenSizeFade) { this(screenSizeFade, null, null); }
    public DecalRendererSettings(float screenSizeFade, @org.jspecify.annotations.Nullable String materialBinding,
                                @org.jspecify.annotations.Nullable String invalidDomainMaterial) {
        this(screenSizeFade, materialBinding, invalidDomainMaterial, "decal");
    }

    public DecalRendererSettings {
        if (!Float.isFinite(screenSizeFade)) throw new IllegalArgumentException("Invalid Niagara decal screen fade");
        java.util.Objects.requireNonNull(materialVariant);
        if (materialBinding != null && invalidDomainMaterial == null)
            throw new IllegalArgumentException("A bound decal material requires the engine default for invalid material domains");
    }
}
