package com.kltyton.lumenfx.effect.niagara.sequence;

import com.kltyton.lumenfx.api.client.NiagaraAgeUpdate;
import java.util.Objects;

/** The Niagara System Life Cycle track's first spawn section and its sequence display settings. */
public record NiagaraSystemLifeCycleTrack(String source, int startFrame, int endFrame, FrameRate displayRate,
        boolean frameLocked, EvaluateBehavior evaluateBehavior, EndBehavior endBehavior,
        NiagaraAgeUpdate.Mode ageUpdateMode, boolean allowScalability) {
    public enum EvaluateBehavior { ActivateIfInactive, None }
    public enum EndBehavior { SetSystemInactive, Deactivate, None }

    public record FrameRate(int numerator, int denominator) {
        public FrameRate {
            if (numerator <= 0 || denominator <= 0) throw new IllegalArgumentException("Invalid MovieScene frame rate");
        }
        public double seconds(double frames) { return frames * denominator / numerator; }
    }

    public NiagaraSystemLifeCycleTrack {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(displayRate, "displayRate");
        Objects.requireNonNull(evaluateBehavior, "evaluateBehavior");
        Objects.requireNonNull(endBehavior, "endBehavior");
        Objects.requireNonNull(ageUpdateMode, "ageUpdateMode");
        if (endFrame < startFrame) throw new IllegalArgumentException("Reversed Niagara life cycle section");
    }
}
