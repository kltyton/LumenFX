package com.kltyton.lumenfx.effect.render.splat;

/** Authored UNiagaraSplatRendererProperties settings passed to the Gaussian Splat renderer. */
public record SplatRendererSettings(float splatScale, float opacityScale, boolean invertToneCurve,
                                    float inverseToneCurveStrength, boolean invertExpandGamut, int rendererVisibility) {
    public SplatRendererSettings {
        if (!Float.isFinite(splatScale) || !Float.isFinite(opacityScale) || !Float.isFinite(inverseToneCurveStrength))
            throw new IllegalArgumentException("Non-finite Niagara splat renderer parameter");
    }
}
