package com.kltyton.lumenfx.effect.noise;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Adapts Minecraft 26.3's {@link PerlinNoise} (float output) to the 26.2 {@code ImprovedNoise}
 * double-precision contract used by shared particle simulation and Unity noise motion.
 */
public final class ImprovedNoise {
    private final PerlinNoise delegate;

    public ImprovedNoise(RandomSource random) {
        this.delegate = new PerlinNoise(random);
    }

    public double noise(double x, double y, double z) {
        return delegate.get(x, y, z);
    }

    public double noiseWithDerivative(double x, double y, double z, double[] derivativeOut) {
        float[] grad = new float[3];
        float value = delegate.noiseWithDerivative(x, y, z, grad);
        if (derivativeOut != null && derivativeOut.length >= 3) {
            derivativeOut[0] = grad[0];
            derivativeOut[1] = grad[1];
            derivativeOut[2] = grad[2];
        }
        return value;
    }
}
