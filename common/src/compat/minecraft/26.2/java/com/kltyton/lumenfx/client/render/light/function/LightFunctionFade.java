package com.kltyton.lumenfx.client.render.light.function;

import com.kltyton.lumenfx.client.render.light.shadow.ShadowResolutionFade;

/** Source shadow-resolution controls also determine the projected light-function fade. */
public record LightFunctionFade(int minimumResolution, int fadeResolution, float texelsPerPixel, float exponent) {
    public static final LightFunctionFade DEFAULT = new LightFunctionFade(32, 64, 1.27324f, .25f);

    public LightFunctionFade {
        minimumResolution = Math.max(0, minimumResolution);
        fadeResolution = Math.max(0, fadeResolution);
    }

    public float alpha(float screenRadius) {
        return ShadowResolutionFade.alpha(screenRadius * texelsPerPixel, fadeResolution, minimumResolution, exponent);
    }
}
