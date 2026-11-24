package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Shuriken's speed limit, per-axis limits and drag settings. */
public record UnityVelocityLimit(List<ScalarFunction> limits, boolean localSpace, double dampen,
                                 ScalarFunction drag, boolean multiplyBySize, boolean multiplyByVelocity,
                                 @Nullable UnitySpaceTransform spaceTransform) {
    public UnityVelocityLimit(List<ScalarFunction> limits, boolean localSpace, double dampen,
                              ScalarFunction drag, boolean multiplyBySize, boolean multiplyByVelocity) {
        this(limits, localSpace, dampen, drag, multiplyBySize, multiplyByVelocity, null);
    }

    public UnityVelocityLimit {
        limits = List.copyOf(limits);
        Objects.requireNonNull(drag);
        if (limits.size() != 1 && limits.size() != 3 || !Double.isFinite(dampen) || dampen < 0 || dampen > 1)
            throw new IllegalArgumentException("Invalid Unity velocity limit");
    }

    public boolean separateAxes() { return limits.size() == 3; }
}
