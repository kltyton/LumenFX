package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;

/** The saved curve contains the integrated rate; lifetime scales both current and previous samples. */
public record SpriteRotationRateModule(int current, int previous, float minimumRate, float maximumRate,
                                       int parameterOffset, StatelessDistribution curve) implements StatelessParticleSimulation.Module {
    @Override public void simulate(StatelessParticleSimulation context) {
        float bias = parameterOffset < 0 ? minimumRate : context.dynamicFloat(parameterOffset);
        float scale = parameterOffset < 0 ? maximumRate - minimumRate : 0f;
        float[] value = context.scratchVector(0), old = context.scratchVector(1);
        for (int row = 0; row < context.count(); row++) {
            float rate = bias + context.randomFloat(row, 0) * scale;
            curve.curve(context, context.normalizedAge(row), value, 1);
            curve.curve(context, context.previousNormalizedAge(row), old, 1);
            float rotation = context.particleFloatOrDefault(current, row, 0f);
            float previousRotation = context.particleFloatOrDefault(previous, row, 0f);
            rotation += context.lifetime(row) * rate * value[0];
            previousRotation += context.lifetime(row) * rate * old[0];
            context.particleFloat(current, row, rotation); context.particleFloat(previous, row, previousRotation);
        }
    }
}
