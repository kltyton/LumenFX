package com.kltyton.lumenfx.effect.render.light.scene;

/** Per-light source properties, separate from renderer-wide shadow allocation controls. */
public record PointShadowLightSettings(float resolutionScale, float bias, float slopeBias, float sharpen, boolean staticShadowing,
                                        boolean forceCachedMoving, float strength, float nearClipCm,
                                        int customResolution, boolean hardShadow) {
    public PointShadowLightSettings(float resolutionScale, float bias, float slopeBias, float sharpen,
                                    boolean staticShadowing, boolean forceCachedMoving) {
        this(resolutionScale, bias, slopeBias, sharpen, staticShadowing, forceCachedMoving, 1, 0, 0, false);
    }

    public PointShadowLightSettings(float resolutionScale, float bias, float slopeBias, float sharpen, boolean staticShadowing) {
        this(resolutionScale, bias, slopeBias, sharpen, staticShadowing, false);
    }
    public PointShadowLightSettings(float resolutionScale, float bias, float slopeBias, float sharpen) {
        this(resolutionScale, bias, slopeBias, sharpen, false);
    }
    public static final PointShadowLightSettings DEFAULT = new PointShadowLightSettings(1, .5f, .5f, 0);
}
