package com.kltyton.lumenfx.effect.render;

/** Material-side fog application; the current view supplies scattering and transmission. */
public record MaterialFog(boolean enabled, boolean perPixel, boolean sky) {
    public MaterialFog(boolean enabled, boolean perPixel) { this(enabled, perPixel, false); }

    public static final MaterialFog NONE = new MaterialFog(false, false);
}
