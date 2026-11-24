package com.kltyton.lumenfx.effect.curve;

import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.VectorKey;
import net.minecraft.world.phys.Vec3;

final class SplineSegmentLength {
    private static final float[] ABSCISSAS = {0, -.5384693f, .5384693f, -.90617985f, .90617985f};
    private static final float[] WEIGHTS = {.5688889f, .47862867f, .47862867f, .23692688f, .23692688f};

    private SplineSegmentLength() { }

    static float integrate(VectorKey a, VectorKey b, float parameter, Vec3 scale) {
        var difference = b.value().subtract(a.value());
        switch (a.interpolation()) {
            case LINEAR -> { return (float)(difference.multiply(scale).length() * parameter); }
            case CONSTANT -> { return parameter == 1 ? (float)difference.multiply(scale).length() : 0; }
            case CUBIC -> {
                var start = a.leave();
                var end = b.arrive();
                var quadratic = difference.scale(-2).add(start).add(end).scale(3);
                var linear = difference.scale(6).subtract(start.scale(4)).subtract(end.scale(2));
                float half = parameter * .5f, length = 0;
                for (int sample = 0; sample < ABSCISSAS.length; sample++) {
                    float alpha = half * (1 + ABSCISSAS[sample]);
                    var derivative = quadratic.scale(alpha).add(linear).scale(alpha).add(start).multiply(scale);
                    length += derivative.length() * WEIGHTS[sample];
                }
                return length * half;
            }
        }
        throw new IllegalStateException("Unknown spline interpolation mode");
    }
}
