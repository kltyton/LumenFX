package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import java.util.List;

/** Facing and alignment take the resolved range minimum without introducing particle randomness. */
public record FacingAlignmentModule(List<Attribute> attributes) implements StatelessParticleSimulation.Module {
    public record Attribute(int current, int previous, StatelessRange value) { }
    public FacingAlignmentModule { attributes = List.copyOf(attributes); }
    @Override public void simulate(StatelessParticleSimulation context) {
        float[] value = context.scratchVector(0);
        for (Attribute attribute : attributes) {
            for (int component = 0; component < 3; component++)
                value[component] = attribute.value().parameterOffset() < 0 ? attribute.value().minimum(component)
                        : context.dynamicFloat(attribute.value().parameterOffset() + component);
            for (int row = 0; row < context.count(); row++) for (int component = 0; component < 3; component++) {
                if (attribute.current() >= 0) context.particleFloat(attribute.current() + component, row, value[component]);
                if (attribute.previous() >= 0) context.particleFloat(attribute.previous() + component, row, value[component]);
            }
        }
    }
}
