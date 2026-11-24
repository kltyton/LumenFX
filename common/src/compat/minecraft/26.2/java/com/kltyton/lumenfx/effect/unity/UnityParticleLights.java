package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public record UnityParticleLights(double ratio, int maximum, boolean randomDistribution, Vec3 color,
                                  double radius, double intensity, ScalarFunction rangeOverLife,
                                  ScalarFunction intensityOverLife, boolean useParticleColor,
                                  boolean sizeAffectsRange, boolean alphaAffectsIntensity, @Nullable Profile profile) {
    public enum Shape { POINT, SPOT }
    public enum Shadows { NONE, HARD, SOFT }

    public record Profile(Shape shape, boolean forwardToUp, Vec3 forward, Vec3 up, float innerAngle, float outerAngle,
                          Shadows shadows, float shadowStrength, float shadowBias, float shadowNormalBias,
                          float shadowNear, int shadowResolution, @Nullable String cookieMaterial) {
        public Profile {
            if (!Double.isFinite(forward.lengthSqr()) || !Double.isFinite(up.lengthSqr())
                    || forward.cross(up).lengthSqr() < 1e-12 || !Float.isFinite(innerAngle) || !Float.isFinite(outerAngle)
                    || !(shadowStrength >= 0 && shadowStrength <= 1) || !(shadowBias >= 0 && Float.isFinite(shadowBias))
                    || !(shadowNormalBias >= 0 && Float.isFinite(shadowNormalBias))
                    || !(shadowNear >= 0 && Float.isFinite(shadowNear)))
                throw new IllegalArgumentException("Invalid Unity particle light profile");
        }
    }

    public UnityParticleLights(double ratio, int maximum, boolean randomDistribution, Vec3 color, double radius,
                               double intensity, ScalarFunction rangeOverLife, ScalarFunction intensityOverLife,
                               boolean useParticleColor, boolean sizeAffectsRange, boolean alphaAffectsIntensity) {
        this(ratio, maximum, randomDistribution, color, radius, intensity, rangeOverLife, intensityOverLife,
                useParticleColor, sizeAffectsRange, alphaAffectsIntensity, null);
    }

    public UnityParticleLights {
        if (!(ratio >= 0 && ratio <= 1) || maximum < 0 || !(radius > 0) || intensity < 0
                || !Double.isFinite(radius) || !Double.isFinite(intensity) || !Double.isFinite(color.lengthSqr()))
            throw new IllegalArgumentException("Invalid Unity particle light settings");
    }
}
