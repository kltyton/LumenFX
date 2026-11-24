package com.kltyton.lumenfx.api.client;

import java.util.Objects;

/** Niagara component age settings. Desired ages drive solo systems and cache playback. */
public record NiagaraAgeUpdate(Mode mode, float desiredAge, float seekDelta, boolean lockToSeekDelta,
                               float maximumSimulationTime, boolean renderWhileSeeking) {
    public enum Mode { TICK_DELTA_TIME, DESIRED_AGE, DESIRED_AGE_NO_SEEK }

    public static final NiagaraAgeUpdate DEFAULT = new NiagaraAgeUpdate(Mode.TICK_DELTA_TIME, 0f, 1f / 30f,
            true, 33f / 1000f, true);

    public NiagaraAgeUpdate {
        Objects.requireNonNull(mode);
        if (!Float.isFinite(desiredAge) || !Float.isFinite(seekDelta) || seekDelta <= 0f
                || !Float.isFinite(maximumSimulationTime))
            throw new IllegalArgumentException("Invalid Niagara component age settings");
    }
}
