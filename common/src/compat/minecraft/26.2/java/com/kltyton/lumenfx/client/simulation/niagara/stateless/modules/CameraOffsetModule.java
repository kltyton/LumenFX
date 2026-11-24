package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;

/** Camera displacement samples current and previous age with the same random call. */
public record CameraOffsetModule(int current, int previous, StatelessDistribution distribution,
                                 int scaleWord) implements StatelessParticleSimulation.Module {
    @Override public void simulate(StatelessParticleSimulation context) {
        float[] value = context.scratchVector(0), old = context.scratchVector(1);
        float scale = context.shaderFloat(scaleWord);
        for (int row = 0; row < context.count(); row++) {
            distribution.samplePair(context, row, 0, context.normalizedAge(row), context.previousNormalizedAge(row), value, old, 1);
            context.particleFloat(current, row, value[0] * scale);
            context.particleFloat(previous, row, old[0] * scale);
        }
    }
}
