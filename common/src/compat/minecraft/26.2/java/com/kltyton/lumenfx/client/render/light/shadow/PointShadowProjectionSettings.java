package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.effect.render.light.scene.PointShadowLightSettings;
import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;

/** Projection values are supplied by the actual shadow allocation and light's source settings. */
public record PointShadowProjectionSettings(float depthBias, float slopeBias, float maximumSlopeBias,
                                             float sharpen, float fade, int quality) {
    public PointShadowProjectionSettings { quality = Math.clamp(quality, 0, 5); }

    public record BiasControls(float depth, float slope, float maximumSlope) {
        public static final BiasControls DEFAULT = new BiasControls(.02f, 3f, 1f);
        public static final BiasControls RECT = new BiasControls(.025f, 2.5f, 1f);
    }

    public record Spot(float casterBias, float slopeBias, float transitionScale, float depthRange, float receiverBias) {
        public static Spot prepare(PointShadowLightSettings light, int outerResolution, float depthRange) {
            int innerResolution = outerResolution - 2 * PointShadowResolution.SPOT_BORDER;
            float bias = Math.clamp(3f * 512f / (depthRange * innerResolution) * 2f * light.bias(), 0f, .1f);
            float transition = 1f / Math.max(1e-5f, (1f / 60f) * 2f * light.bias());
            return new Spot(bias, Math.max(0f, bias * 3f * light.slopeBias()), transition, depthRange, .5f);
        }
    }

    public static PointShadowProjectionSettings prepare(PointShadowLightSettings light, int allocatedResolution,
                                                         BiasControls controls, float fade, int quality) {
        if (allocatedResolution <= 0) throw new IllegalArgumentException("Point shadow requires its actual allocated resolution");
        float depth = controls.depth() * 512f / allocatedResolution;
        depth *= 2f * light.bias();
        float slopeScale = controls.slope() * light.slopeBias();
        return new PointShadowProjectionSettings(UnrealRendererMath.max(depth, 0f), UnrealRendererMath.max(depth * slopeScale, 0f),
                controls.maximumSlope(), light.sharpen() * 7f + 1f, fade * light.strength(), quality);
    }
}
