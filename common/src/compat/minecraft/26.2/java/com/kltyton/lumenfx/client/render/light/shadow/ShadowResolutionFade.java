package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.client.render.math.NativeSceneMath;

/** Shared source resolution fade for projected shadows and light functions. */
public final class ShadowResolutionFade {
    private ShadowResolutionFade() { }
    public static float alpha(float resolution, int fadeResolution, int minimumResolution, float exponent) {
        if (resolution > fadeResolution) return 1;
        if (!(resolution > minimumResolution)) return 0;
        if (minimumResolution >= fadeResolution)
            return 1 - NativeSceneMath.power(resolution - minimumResolution, exponent);
        float inverseRange = 1f / (fadeResolution - minimumResolution);
        float first = NativeSceneMath.power(inverseRange, exponent);
        float ratio = (resolution - minimumResolution) * inverseRange;
        return (NativeSceneMath.power(ratio, exponent) - first) / (1 - first);
    }
}
