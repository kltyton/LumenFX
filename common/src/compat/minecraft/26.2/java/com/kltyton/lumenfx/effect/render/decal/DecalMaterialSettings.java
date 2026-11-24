package com.kltyton.lumenfx.effect.render.decal;

public record DecalMaterialSettings(int outputs, Blend blend) {
    public static final int BASE_COLOR = 1, NORMAL = 2, MATERIAL = 4, EMISSIVE = 8, AMBIENT_OCCLUSION = 16;
    public enum Blend { TRANSLUCENT, ALPHA_COMPOSITE, MODULATE }

    public DecalMaterialSettings {
        if (blend == null || (outputs & ~31) != 0) throw new IllegalArgumentException("Invalid decal material outputs");
        if (blend == Blend.ALPHA_COMPOSITE) outputs &= ~NORMAL;
    }

    public boolean writes(int channels) { return (outputs & channels) != 0; }
    public boolean writesDBuffer() { return writes(BASE_COLOR | NORMAL | MATERIAL); }
}
