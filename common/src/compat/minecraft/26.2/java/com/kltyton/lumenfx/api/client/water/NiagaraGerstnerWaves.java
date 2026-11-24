package com.kltyton.lumenfx.api.client.water;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** The Water plugin's saved Gerstner spectrum and CPU water surface evaluator. */
public final class NiagaraGerstnerWaves implements NiagaraWaterWaves {
    private static final float PI = (float)Math.PI;
    private static final float TWO_PI = 2f * PI;
    private static final float HALF_PI = PI * .5f;
    private static final float INV_PI = 1f / PI;
    private static final double BLEND_ZONE = 400;

    public record Wave(float amplitude, Vec3 direction, double vectorX, double vectorY, float speed, float wka, float q) {
        public Wave { Objects.requireNonNull(direction); }

        public static Wave fromParameters(float wavelength, float amplitude, float steepness, Vec3 direction) {
            float dispersion = TWO_PI / wavelength;
            float wka = amplitude * dispersion;
            return new Wave(amplitude, direction, direction.x * dispersion, direction.y * dispersion,
                    (float)Math.sqrt(dispersion * 980f), wka, amplitude * (steepness / wka));
        }
    }

    private record Angles(float sine, float cosine) { }
    private record Offset(float alongWave, double height, Vec3 normal) { }

    private final List<Wave> waves;

    public NiagaraGerstnerWaves(List<Wave> waves) { this.waves = List.copyOf(waves); }

    @Override public Sample sample(Vec3 worldPosition, float depth, float seconds, boolean simple) {
        double x = (float)(worldPosition.x - NiagaraPositionParameters.origin(worldPosition.x));
        double y = (float)(worldPosition.y - NiagaraPositionParameters.origin(worldPosition.y));
        float height = 0;
        Vec3 normal = new Vec3(0, 0, 1);
        for (var wave : waves) {
            float time = wave.speed() * seconds;
            float phase = phase(wave, x, y, time);
            if (simple) {
                height += blend(wave, x, y, time, new Angles(0, (float)Math.cos(phase))).cosine() * wave.amplitude();
                continue;
            }
            var left = offset(wave, x, y, time, phase);
            if (wave.q() == 0) {
                height = (float)(height + left.height());
                normal = normal.add(left.normal());
                continue;
            }
            float cycle = phase >= 0 ? phase % TWO_PI : TWO_PI - Math.abs(phase % TWO_PI);
            boolean sampleRight = cycle < PI;
            float distance = sampleRight ? wave.q() : -wave.q();
            double guessX = x + wave.direction().x * distance;
            double guessY = y + wave.direction().y * distance;
            var guess = offset(wave, guessX, guessY, time, phase(wave, guessX, guessY, time));
            var right = new Offset(guess.alongWave() + distance, guess.height(), guess.normal());
            if (!sampleRight) {
                var swap = left;
                left = right;
                right = swap;
            }
            float width = right.alongWave() - left.alongWave();
            float fraction = -left.alongWave() / (width > 0 ? width : 1);
            height += (float)(left.height() + fraction * (right.height() - left.height()));
            normal = normal.add(left.normal().lerp(right.normal(), fraction));
        }
        double squared = normal.lengthSqr();
        if (squared < 1e-8) normal = Vec3.ZERO;
        else if (squared != 1) normal = normal.scale(1 / Math.sqrt(squared));
        return new Sample(height, normal);
    }

    @Override public float attenuation(Vec3 position, float depth, float targetDepth) {
        float strength = (float)Math.exp(-Math.max(depth, 0f) / (targetDepth / 2f));
        return Math.clamp(1f - strength, 0f, 1f);
    }

    private static Offset offset(Wave wave, double x, double y, float time, float phase) {
        var angles = blend(wave, x, y, time, sinCos(phase));
        float normal = angles.sine() * wave.wka();
        return new Offset(-wave.q() * angles.sine(), angles.cosine() * wave.amplitude(),
                new Vec3(normal * wave.direction().x, normal * wave.direction().y, 0));
    }

    private static float phase(Wave wave, double x, double y, float time) {
        return (float)(x * wave.vectorX() + y * wave.vectorY() - time);
    }

    private static Angles blend(Wave wave, double x, double y, float time, Angles original) {
        double borderX = NiagaraPositionParameters.TILE_SIZE / 2 - Math.abs(x);
        double borderY = NiagaraPositionParameters.TILE_SIZE / 2 - Math.abs(y);
        if (borderX >= BLEND_ZONE && borderY >= BLEND_ZONE) return original;
        double alpha = Math.clamp(borderX / BLEND_ZONE, 0, 1) * Math.clamp(borderY / BLEND_ZONE, 0, 1);
        var border = sinCos(phase(wave, borderX, borderY, time));
        return new Angles((float)(border.sine() + alpha * (original.sine() - border.sine())),
                (float)(border.cosine() + alpha * (original.cosine() - border.cosine())));
    }

    private static Angles sinCos(float angle) {
        float quotient = (INV_PI * .5f) * angle;
        float remainder = angle - TWO_PI * (long)(quotient + (angle >= 0 ? .5f : -.5f));
        float cosineSign = 1f;
        if (remainder > HALF_PI) {
            remainder = PI - remainder;
            cosineSign = -1f;
        } else if (remainder < -HALF_PI) {
            remainder = -PI - remainder;
            cosineSign = -1f;
        }
        float square = remainder * remainder;
        float sine = (((((-2.3889859e-08f * square + 2.7525562e-06f) * square - .00019840874f)
                * square + .0083333310f) * square - .16666667f) * square + 1f) * remainder;
        float cosine = (((((-2.6051615e-07f * square + 2.4760495e-05f) * square - .0013888378f)
                * square + .041666638f) * square - .5f) * square + 1f) * cosineSign;
        return new Angles(sine, cosine);
    }
}
