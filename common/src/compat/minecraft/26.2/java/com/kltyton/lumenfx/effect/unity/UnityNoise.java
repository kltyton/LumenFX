package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;

public record UnityNoise(List<ScalarFunction> strength, List<ScalarFunction> remap, double frequency, boolean damping,
                         int octaves, double octaveMultiplier, double octaveScale, int quality,
                         ScalarFunction scrollSpeed, ScalarFunction position, ScalarFunction rotation,
                         ScalarFunction size, boolean forwardToUp) {
    public UnityNoise {
        strength = List.copyOf(strength);
        remap = List.copyOf(remap);
        if (strength.size() != 3 || !remap.isEmpty() && remap.size() != 3
                || !Double.isFinite(frequency) || frequency < 0 || octaves < 1 || octaves > 4
                || !Double.isFinite(octaveMultiplier) || octaveMultiplier < 0
                || !Double.isFinite(octaveScale) || octaveScale <= 0 || quality < 1 || quality > 3)
            throw new IllegalArgumentException("Invalid Unity Noise settings");
    }
}
