/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.shape;

public enum ShapeArcMode {
    Random,
    Loop,
    PingPong,
    BurstSpread;

    public boolean usesArcSpeed() {
        return this == Loop || this == PingPong;
    }

    public float sample(float spread,
                        float speed,
                        int emitterAge,
                        int batchIndex,
                        int batchCount,
                        double randomValue) {
        return samplePhase(spread, emitterAge / 20.0f * speed, batchIndex, batchCount, randomValue);
    }

    public float samplePhase(float spread, float phase, int batchIndex, int batchCount, double randomValue) {
        var raw = switch (this) {
            case Random -> (float) randomValue;
            case Loop -> positiveFraction(phase);
            case PingPong -> pingPong(phase);
            case BurstSpread -> batchCount <= 1 ? 0.0f : (float) batchIndex / batchCount;
        };
        return applySpread(raw, spread);
    }

    private static float positiveFraction(float value) {
        return value - (float) Math.floor(value);
    }

    private static float pingPong(float value) {
        var phase = positiveFraction(value / 2.0f) * 2.0f;
        return phase <= 1.0f ? phase : 2.0f - phase;
    }

    public static float applySpread(float value, float spread) {
        value = clamp(value, 0.0f, 1.0f);
        if (spread <= 0.0f) {
            return value;
        }
        var clampedSpread = clamp(spread, 0.0f, 1.0f);
        if (clampedSpread >= 1.0f) {
            return 0.0f;
        }
        return (float) Math.floor(value / clampedSpread) * clampedSpread;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
