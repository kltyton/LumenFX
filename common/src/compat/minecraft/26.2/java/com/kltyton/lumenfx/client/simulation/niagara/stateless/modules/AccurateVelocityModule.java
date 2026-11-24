package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;

/** Both velocity attributes receive the same displacement-derived value in the native analytic pass. */
public record AccurateVelocityModule(int position, int previousPosition, int velocity,
                                     int previousVelocity) implements StatelessParticleSimulation.Module {
    @Override public void simulate(StatelessParticleSimulation context) {
        float[] result = context.scratchVector(0);
        for (int row = 0; row < context.count(); row++) {
            for (int component = 0; component < 3; component++) {
                float current = position < 0 ? 0f : context.particleFloat(position + component, row);
                float previous = previousPosition < 0 ? 0f : context.particleFloat(previousPosition + component, row);
                result[component] = (current - previous) * context.inverseDeltaTime();
            }
            for (int component = 0; component < 3; component++) {
                if (velocity >= 0) context.particleFloat(velocity + component, row, result[component]);
                if (previousVelocity >= 0) context.particleFloat(previousVelocity + component, row, result[component]);
            }
        }
    }
}
