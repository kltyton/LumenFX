package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import java.util.List;

/** Native light distributions share random call zero and scale only the radius with owner scale. */
public record LightAttributesModule(List<Attribute> attributes, int radiusScaleWord) implements StatelessParticleSimulation.Module {
    public record Attribute(int component, boolean radius, StatelessDistribution distribution) { }
    public LightAttributesModule { attributes = List.copyOf(attributes); }
    @Override public void simulate(StatelessParticleSimulation context) {
        float[] value = context.scratchVector(0);
        float radiusScale = context.shaderFloat(radiusScaleWord);
        for (int row = 0; row < context.count(); row++) for (Attribute attribute : attributes) {
            attribute.distribution().sample(context, row, 0, context.normalizedAge(row), value, 1);
            context.particleFloat(attribute.component(), row, value[0] * (attribute.radius() ? radiusScale : 1f));
        }
    }
}
