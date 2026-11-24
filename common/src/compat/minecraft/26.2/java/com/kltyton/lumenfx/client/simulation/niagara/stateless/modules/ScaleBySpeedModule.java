package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.math.SourceFloatMath;

/** Sprite and mesh size use squared speed and apply one scale to both temporal attributes. */
public record ScaleBySpeedModule(int width, int current, int previous, int position, int previousPosition, int velocity,
                                  Value threshold, Value minimum, Value maximum,
                                  StatelessDistribution distribution) implements StatelessParticleSimulation.Module {
    public record Value(float[] defaults, int offset) {
        public Value { defaults = defaults.clone(); }
        @Override public float[] defaults() { return defaults.clone(); }
        float get(StatelessParticleSimulation context, int component) { return offset < 0 ? defaults[component] : context.dynamicFloat(offset + component); }
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        float limit = threshold.get(context, 0), norm = limit > 0f ? 1f / (limit * limit) : 0f;
        float[] factorBias = context.scratchVector(0), factorScale = context.scratchVector(1), sample = context.scratchVector(2);
        float[] value = context.scratchVector(3), old = context.scratchVector(4);
        for (int component = 0; component < width; component++) {
            factorBias[component] = minimum.get(context, component);
            factorScale[component] = maximum.get(context, component) - factorBias[component];
        }
        for (int row = 0; row < context.count(); row++) {
            float vx, vy, vz;
            if (previousPosition >= 0) {
                vx = ((position < 0 ? 0f : context.particleFloat(position, row)) - context.particleFloat(previousPosition, row)) * context.inverseDeltaTime();
                vy = ((position < 0 ? 0f : context.particleFloat(position + 1, row)) - context.particleFloat(previousPosition + 1, row)) * context.inverseDeltaTime();
                vz = ((position < 0 ? 0f : context.particleFloat(position + 2, row)) - context.particleFloat(previousPosition + 2, row)) * context.inverseDeltaTime();
            } else {
                vx = velocity < 0 ? 0f : context.particleFloat(velocity, row);
                vy = velocity < 0 ? 0f : context.particleFloat(velocity + 1, row);
                vz = velocity < 0 ? 0f : context.particleFloat(velocity + 2, row);
            }
            float speed = vx * vx + vy * vy + vz * vz;
            distribution.sample(context, row, 0, SourceFloatMath.clamp(speed * norm, 0f, 1f), sample, 1);
            float interpolation = SourceFloatMath.clamp(sample[0], 0f, 1f);
            for (int component = 0; component < width; component++) {
                float factor = factorBias[component] + factorScale[component] * interpolation;
                value[component] = (current < 0 ? 1f : context.particleFloat(current + component, row)) * factor;
                old[component] = (previous < 0 ? 1f : context.particleFloat(previous + component, row)) * factor;
            }
            for (int component = 0; component < width; component++) {
                if (current >= 0) context.particleFloat(current + component, row, value[component]);
                if (previous >= 0) context.particleFloat(previous + component, row, old[component]);
            }
        }
    }
}
