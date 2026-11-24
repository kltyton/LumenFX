package com.kltyton.lumenfx.effect.render.light;

/** Niagara's component lighting, shadow, and ray tracing flags. */
public record PrimitiveLighting(int channels, boolean castsDynamicShadow, boolean castsContactShadow,
                                boolean rayTracingFarField) {
    public static final PrimitiveLighting DEFAULT = new PrimitiveLighting(1, false, false);

    public PrimitiveLighting(int channels, boolean castsDynamicShadow, boolean castsContactShadow) {
        this(channels, castsDynamicShadow, castsContactShadow, false);
    }

    public PrimitiveLighting(int channels, boolean castsContactShadow) {
        this(channels, false, castsContactShadow);
    }

    public PrimitiveLighting {
        if ((channels & ~7) != 0) throw new IllegalArgumentException("Lighting channels must use a three-bit mask");
    }
}
