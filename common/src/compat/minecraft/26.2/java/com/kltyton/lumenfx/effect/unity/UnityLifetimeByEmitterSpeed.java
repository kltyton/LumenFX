package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.Objects;

/** A multiplier of the birth lifetime, sampled from the motion of the emitter. */
public record UnityLifetimeByEmitterSpeed(UnityInheritVelocity.Source source, UnitySpeedRange range, ScalarFunction value) {
    public UnityLifetimeByEmitterSpeed {
        Objects.requireNonNull(source);
        Objects.requireNonNull(range);
        Objects.requireNonNull(value);
    }

    public double multiplier(double speed, double random) {
        return value.sample(range.progress(speed), random);
    }
}
