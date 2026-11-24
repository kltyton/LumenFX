package com.kltyton.lumenfx.api.client.component;

import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack.FrameRate;
import java.util.Objects;

/** A MovieScene evaluation context in the track's own sequence time. */
public record NiagaraMovieSceneEvaluation(double time, FrameRate frameRate, double rangeLower, boolean lowerInclusive,
                                           double rangeUpper, boolean upperInclusive, boolean preRoll, boolean recordingCacheTrack) {
    public NiagaraMovieSceneEvaluation(double time, FrameRate frameRate, double rangeLower, boolean lowerInclusive,
                                      double rangeUpper, boolean upperInclusive, boolean preRoll) {
        this(time, frameRate, rangeLower, lowerInclusive, rangeUpper, upperInclusive, preRoll, false);
    }

    public NiagaraMovieSceneEvaluation {
        Objects.requireNonNull(frameRate, "frameRate");
        if (!Double.isFinite(time) || Double.isNaN(rangeLower) || Double.isNaN(rangeUpper) || rangeLower > rangeUpper)
            throw new IllegalArgumentException("Invalid MovieScene evaluation time or range");
    }

    public boolean visits(int frame) {
        return (frame > rangeLower || lowerInclusive && frame == rangeLower)
                && (frame < rangeUpper || upperInclusive && frame == rangeUpper);
    }
}
