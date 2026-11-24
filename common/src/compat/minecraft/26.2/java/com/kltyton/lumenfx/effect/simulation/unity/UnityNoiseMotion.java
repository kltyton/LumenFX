package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.effect.unity.UnityNoise;
import java.util.Arrays;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/** Per-emitter scratch for the Shuriken curl field. The host Perlin sampler provides its analytic gradient. */
public final class UnityNoiseMotion {
    private final UnityNoise definition;
    private final double duration;
    private final boolean loops;
    private final double scrollRandom;
    private final double[] first = new double[3], second = new double[3], third = new double[3];
    private double phaseStart;
    public double x, y, z, rotationX, rotationY, rotationZ, sizeScale;

    public UnityNoiseMotion(UnityNoise definition, double duration, boolean loops, double scrollRandom) {
        this.definition = definition;
        this.duration = duration;
        this.loops = loops;
        this.scrollRandom = scrollRandom;
    }

    public void phaseStart(double time) { phaseStart = time; }

    public void sample(ImprovedNoise noise, double px, double py, double pz, double time, double age, double random) {
        double elapsed = Math.max(0, time - phaseStart) / duration;
        double cycles = loops ? Math.floor(elapsed) : 0;
        var scroll = definition.scrollSpeed();
        double shift = duration * scroll.integral(0, Math.min(1, elapsed - cycles), scrollRandom);
        if (cycles != 0) shift += duration * cycles * scroll.integral(0, 1, scrollRandom);
        double frequency = definition.frequency();
        double sx = px * frequency + shift, sy = py * frequency, sz = pz * frequency;
        double vx = 0, vy = 0, vz = 0, amplitude = 1;
        for (int octave = 0; octave < definition.octaves(); octave++) {
            gradient(noise, sx, sy, sz, first);
            double[] b = first, c = first;
            if (definition.quality() >= 2) {
                gradient(noise, sx + 17.19, sy - 9.83, sz + 23.47, second);
                b = second;
            }
            if (definition.quality() == 3) {
                gradient(noise, sx - 11.73, sy + 31.57, sz + 7.91, third);
                c = third;
            }
            vx += (c[1] - b[2]) * amplitude;
            vy += (first[2] - c[0]) * amplitude;
            vz += (b[0] - first[1]) * amplitude;
            sx *= definition.octaveScale();
            sy *= definition.octaveScale();
            sz *= definition.octaveScale();
            amplitude *= definition.octaveMultiplier();
        }
        double damping = definition.damping() && frequency > 0 ? 1 / frequency : 1;
        vx = axis(0, vx * damping, age, random);
        vy = axis(1, vy * damping, age, random);
        vz = axis(2, vz * damping, age, random);
        double position = definition.position().sample(age, random) * 2;
        x = vx * position;
        y = vy * position;
        z = vz * position;
        double rotation = definition.rotation().sample(age, random);
        rotationX = vx * rotation;
        rotationY = (definition.forwardToUp() ? -vz : vy) * rotation;
        rotationZ = -(definition.forwardToUp() ? vy : vz) * rotation;
        sizeScale = Math.max(0, 1 + (vx + vy + vz) / 3 * definition.size().sample(age, random));
    }

    private double axis(int index, double value, double age, double random) {
        if (!definition.remap().isEmpty())
            value = definition.remap().get(index).sample(Math.clamp((value + 1) * .5, 0, 1), random);
        return definition.strength().get(index).sample(age, random) * value;
    }

    private static void gradient(ImprovedNoise noise, double x, double y, double z, double[] output) {
        Arrays.fill(output, 0);
        noise.noiseWithDerivative(x, y, z, output);
    }
}
