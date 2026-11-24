package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import java.util.List;

/** Material channels retain their parameter-specific random call offsets and explicit disabled zeros. */
public record DynamicMaterialModule(List<Parameter> parameters) implements StatelessParticleSimulation.Module {
    public record Parameter(int index, int mask, int component, List<StatelessDistribution> distributions) {
        public Parameter { distributions = List.copyOf(distributions); }
    }
    public DynamicMaterialModule { parameters = List.copyOf(parameters); }
    @Override public void simulate(StatelessParticleSimulation context) {
        float[] value = context.scratchVector(0);
        for (int row = 0; row < context.count(); row++) for (Parameter parameter : parameters) {
            for (int channel = 0; channel < 4; channel++) {
                float result = 0f;
                if ((parameter.mask() & 1 << channel) != 0) {
                    parameter.distributions().get(channel).sample(context, row, parameter.index() * 4 + channel, context.normalizedAge(row), value, 1);
                    result = value[0];
                }
                context.particleFloat(parameter.component() + channel, row, result);
            }
        }
    }
}
