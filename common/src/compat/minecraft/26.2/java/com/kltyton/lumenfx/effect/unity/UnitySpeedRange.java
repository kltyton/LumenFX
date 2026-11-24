package com.kltyton.lumenfx.effect.unity;

/** Shuriken maps speed to the clamped input of a curve or gradient. */
public record UnitySpeedRange(double minimum, double maximum) {
    public UnitySpeedRange {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum < 0 || maximum < minimum)
            throw new IllegalArgumentException("Invalid Unity speed range");
    }

    public double progress(double speed) {
        if (speed <= minimum) return 0;
        if (speed >= maximum) return 1;
        return (speed - minimum) / (maximum - minimum);
    }
}
