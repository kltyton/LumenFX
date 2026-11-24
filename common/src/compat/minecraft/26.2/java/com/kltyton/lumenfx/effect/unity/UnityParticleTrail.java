package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ColorFunction;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;

public record UnityParticleTrail(double ratio, double minimumVertexDistance, boolean worldSpace,
                                 boolean dieWithParticles, boolean sizeAffectsWidth, boolean sizeAffectsLifetime,
                                 boolean inheritColor, ScalarFunction lifetime, ScalarFunction widthOverTrail,
                                 ColorFunction colorOverTrail, ColorFunction colorOverLifetime, Mode mode,
                                 double maximumParticleDeltaTime) {
    public enum Mode { PER_PARTICLE, RIBBON }
    public static final double DEFAULT_MAXIMUM_PARTICLE_DELTA_TIME = 0.03;

    public UnityParticleTrail(double ratio, double minimumVertexDistance, boolean worldSpace,
                              boolean dieWithParticles, boolean sizeAffectsWidth, boolean sizeAffectsLifetime,
                              boolean inheritColor, ScalarFunction lifetime, ScalarFunction widthOverTrail,
                              ColorFunction colorOverTrail, ColorFunction colorOverLifetime, Mode mode) {
        this(ratio, minimumVertexDistance, worldSpace, dieWithParticles, sizeAffectsWidth, sizeAffectsLifetime,
                inheritColor, lifetime, widthOverTrail, colorOverTrail, colorOverLifetime, mode,
                DEFAULT_MAXIMUM_PARTICLE_DELTA_TIME);
    }

    public UnityParticleTrail(double ratio, double minimumVertexDistance, boolean worldSpace,
                              boolean dieWithParticles, boolean sizeAffectsWidth, boolean sizeAffectsLifetime,
                              boolean inheritColor, ScalarFunction lifetime, ScalarFunction widthOverTrail,
                              ColorFunction colorOverTrail, ColorFunction colorOverLifetime) {
        this(ratio, minimumVertexDistance, worldSpace, dieWithParticles, sizeAffectsWidth, sizeAffectsLifetime,
                inheritColor, lifetime, widthOverTrail, colorOverTrail, colorOverLifetime, Mode.PER_PARTICLE);
    }

    public UnityParticleTrail {
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1
                || !Double.isFinite(minimumVertexDistance) || minimumVertexDistance < 0 || mode == null
                || !Double.isFinite(maximumParticleDeltaTime) || maximumParticleDeltaTime <= 0)
            throw new IllegalArgumentException("Invalid Unity particle trail");
    }
}
