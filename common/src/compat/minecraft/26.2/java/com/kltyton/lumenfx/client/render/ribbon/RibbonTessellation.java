package com.kltyton.lumenfx.client.render.ribbon;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraRibbonScalability;
import com.kltyton.lumenfx.effect.render.RibbonSettings;

/** Emitter-owned curvature history; numeric workers never share it between emitters. */
public final class RibbonTessellation {
    private double previousLength, angle, curvature, twistAngle, twistCurvature;
    private float sourceLength, sourceAngle, sourceCurvature, sourceTwistAngle, sourceTwistCurvature;

    public int updateSource(RibbonSettings settings, NiagaraRibbonScalability scalability,
                            NiagaraCpuRibbonData.Metrics metrics, boolean hasTwist,
                            float viewDistanceSquared, float lodDistanceFactor) {
        if (metrics.length() > 0) {
            float inverse = 1f / Math.max(1f, metrics.length());
            float averaging = sourceLength / (metrics.length() + sourceLength);
            sourceLength = metrics.length();
            float nextAngle = metrics.weightedAngle() * inverse;
            float nextLength = metrics.weightedLength() * inverse;
            float nextCurvature = nextLength / Math.max(1.e-8f, Math.abs((float)Math.sin(nextAngle)));
            sourceAngle = nextAngle + averaging * (Math.max(sourceAngle, nextAngle) - nextAngle);
            sourceCurvature = nextCurvature + averaging * (Math.max(sourceCurvature, nextCurvature) - nextCurvature);
            if (hasTwist) {
                float nextTwist = metrics.weightedTwist() * inverse, nextWidth = metrics.weightedWidth() * inverse;
                sourceTwistAngle = nextTwist + averaging * (Math.max(sourceTwistAngle, nextTwist) - nextTwist);
                sourceTwistCurvature = nextWidth + averaging * (Math.max(sourceTwistCurvature, nextWidth) - nextWidth);
            }
        } else {
            sourceLength = sourceAngle = sourceCurvature = sourceTwistAngle = sourceTwistCurvature = 0;
        }
        return sourceSubdivisions(settings, scalability, viewDistanceSquared, lodDistanceFactor);
    }

    public int sourceSubdivisions(RibbonSettings settings, NiagaraRibbonScalability scalability,
                                  float viewDistanceSquared, float lodDistanceFactor) {
        int maximum = scalability.maximum(settings);
        if (scalability.constant(settings)) return maximum;
        if (!scalability.enabled() || maximum <= 1 || sourceCurvature <= 1.e-8f) return 1;
        boolean custom = settings.tessellationMode() == RibbonSettings.TessellationMode.CUSTOM;
        float angleLimit = custom ? (float)settings.tessellationAngle() : scalability.angle();
        float minimum = angleLimit == 0 ? maximum : Math.max(1f,
                Math.max(sourceTwistAngle, sourceAngle) / Math.max(1.e-8f, angleLimit));
        float screenPercentage = !custom || settings.screenSpace() ? scalability.screenPercentage() : 0f;
        float error = Math.max(scalability.minimumDisplacementCm(), screenPercentage * (float)Math.sqrt(viewDistanceSquared) / lodDistanceFactor);
        float tessellation = sourceAngle / Math.max(.002f,
                com.kltyton.lumenfx.client.render.math.UnrealRendererMath.acosFast(sourceCurvature / (sourceCurvature + error)));
        if (sourceTwistAngle > 0 && sourceTwistCurvature > 0)
            tessellation = Math.max(tessellation, sourceTwistAngle / Math.max(.002f,
                    com.kltyton.lumenfx.client.render.math.UnrealRendererMath.acosFast(sourceTwistCurvature / (sourceTwistCurvature + error))));
        int rounded = com.kltyton.lumenfx.client.render.math.UnrealRendererMath.roundToInt(tessellation);
        int roundedMinimum = com.kltyton.lumenfx.client.render.math.UnrealRendererMath.roundToInt(minimum);
        return rounded < roundedMinimum ? roundedMinimum : rounded > maximum ? maximum : rounded;
    }

    public int update(RibbonSettings settings, double totalLength, double weightedLength,
                      double weightedAngle, double viewDistance) {
        return update(settings, totalLength, weightedLength, weightedAngle, 0, 0, false, viewDistance);
    }

    public int update(RibbonSettings settings, double totalLength, double weightedLength,
                      double weightedAngle, double weightedTwist, double weightedWidth,
                      boolean hasTwist, double viewDistance) {
        return update(settings, totalLength, weightedLength, weightedAngle, weightedTwist, weightedWidth,
                hasTwist, viewDistance, 1);
    }

    public int update(RibbonSettings settings, double totalLength, double weightedLength,
                      double weightedAngle, double weightedTwist, double weightedWidth,
                      boolean hasTwist, double viewDistance, double lodDistanceFactor) {
        if (totalLength <= 0) {
            previousLength = angle = curvature = twistAngle = twistCurvature = 0;
            return settings.constantTessellation() ? settings.maximumTessellation() : 1;
        }
        double weight = previousLength / (totalLength + previousLength);
        previousLength = totalLength;
        double inverseLength = 1 / Math.max(.01, totalLength);
        double nextAngle = weightedAngle * inverseLength;
        double nextCurvature = weightedLength * inverseLength / Math.max(1e-8, Math.abs(Math.sin(nextAngle)));
        angle = nextAngle + (Math.max(angle, nextAngle) - nextAngle) * weight;
        curvature = nextCurvature + (Math.max(curvature, nextCurvature) - nextCurvature) * weight;
        if (hasTwist) {
            double nextTwist = weightedTwist * inverseLength, nextWidth = weightedWidth * inverseLength;
            twistAngle = nextTwist + (Math.max(twistAngle, nextTwist) - nextTwist) * weight;
            twistCurvature = nextWidth + (Math.max(twistCurvature, nextWidth) - nextWidth) * weight;
        }
        return subdivisions(settings, viewDistance, lodDistanceFactor);
    }

    public int subdivisions(RibbonSettings settings, double viewDistance, double lodDistanceFactor) {
        if (settings.constantTessellation()) return settings.maximumTessellation();
        if (settings.maximumTessellation() == 1 || curvature <= 1e-8) return 1;
        double minimum = settings.tessellationAngle() == 0 ? settings.maximumTessellation()
                : Math.max(1, Math.max(angle, twistAngle) / settings.tessellationAngle());
        minimum = Math.min(minimum, settings.maximumTessellation());
        double error = Math.max(.005, settings.screenSpace() ? .002 * viewDistance / lodDistanceFactor : 0);
        double tessellation = angle / Math.max(.002, acosFast(curvature / (curvature + error)));
        if (twistAngle > 0 && twistCurvature > 0)
            tessellation = Math.max(tessellation, twistAngle / Math.max(.002, acosFast(twistCurvature / (twistCurvature + error))));
        return (int)Math.clamp(Math.floor(tessellation + .5), Math.floor(minimum + .5), settings.maximumTessellation());
    }

    public static double acosFast(double value) {
        double x = Math.clamp(Math.abs(value), 0, 1);
        double result = (-.156583 * x + Math.PI * .5) * Math.sqrt(1 - x);
        return value >= 0 ? result : Math.PI - result;
    }
}
