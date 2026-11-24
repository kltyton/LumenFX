package com.kltyton.lumenfx.effect.render.volume;

import org.jspecify.annotations.Nullable;

/** Authored Niagara settings passed to the heterogeneous volume lighting path. */
public record VolumeRendererSettings(float stepFactor, float shadowStepFactor, float shadowBiasFactor,
                                     float lightingDownsampleFactor, @Nullable String materialBinding, String materialVariant) {
    public VolumeRendererSettings {
        if (!Float.isFinite(stepFactor) || !Float.isFinite(shadowStepFactor) || !Float.isFinite(shadowBiasFactor)
                || !Float.isFinite(lightingDownsampleFactor))
            throw new IllegalArgumentException("Non-finite Niagara volume renderer factor");
    }
}
