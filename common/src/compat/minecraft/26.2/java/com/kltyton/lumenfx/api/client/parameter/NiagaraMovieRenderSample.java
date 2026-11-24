package com.kltyton.lumenfx.api.client.parameter;

/** The temporal sample submitted by an offline render host for this effect. */
public record NiagaraMovieRenderSample(boolean active, int sampleCount, int sampleIndex, double sequenceFramesPerSecond) {
    public static final NiagaraMovieRenderSample INACTIVE = new NiagaraMovieRenderSample(false, 1, 0, 0);

    public NiagaraMovieRenderSample {
        if (sampleCount < 1 || sampleIndex < 0 || sampleIndex >= sampleCount
                || !Double.isFinite(sequenceFramesPerSecond) || sequenceFramesPerSecond < 0)
            throw new IllegalArgumentException("Invalid Niagara movie render sample");
    }
}
