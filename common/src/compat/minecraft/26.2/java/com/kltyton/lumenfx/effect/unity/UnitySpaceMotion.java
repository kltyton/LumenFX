package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Additional Velocity over Lifetime inputs and cross-space force curves. */
public record UnitySpaceMotion(List<ScalarFunction> velocity, List<ScalarFunction> force,
                               @Nullable ScalarFunction speedModifier, @Nullable Orbit orbit,
                               @Nullable UnitySpaceTransform velocityTransform,
                               @Nullable UnitySpaceTransform forceTransform,
                               boolean randomizedForce) {
    public record Orbit(List<ScalarFunction> angular, List<ScalarFunction> offset, ScalarFunction radial) {
        public Orbit {
            angular = List.copyOf(angular);
            offset = List.copyOf(offset);
            if (angular.size() != 3 || offset.size() != 3)
                throw new IllegalArgumentException("Unity orbit requires three angular and offset axes");
        }
    }

    public UnitySpaceMotion(List<ScalarFunction> velocity, List<ScalarFunction> force,
                            @Nullable ScalarFunction speedModifier) {
        this(velocity, force, speedModifier, null, null, null, false);
    }

    public UnitySpaceMotion(List<ScalarFunction> velocity, List<ScalarFunction> force,
                            @Nullable ScalarFunction speedModifier, @Nullable Orbit orbit) {
        this(velocity, force, speedModifier, orbit, null, null, false);
    }

    public UnitySpaceMotion {
        velocity = List.copyOf(velocity);
        force = List.copyOf(force);
        if ((!velocity.isEmpty() && velocity.size() != 3) || (!force.isEmpty() && force.size() != 3))
            throw new IllegalArgumentException("Unity space motion requires three axes");
    }
}
