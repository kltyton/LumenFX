package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessDistribution;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Color, mesh, ribbon and sprite scaling retain their source defaults and multiplication order. */
public final class ScaleAttributeModule implements StatelessParticleSimulation.Module {
    public enum Kind {
        COLOR(4, 1f), MESH(3, 1f), RIBBON(1, 0f), SPRITE(2, 0f);
        final int width;
        final float defaultValue;
        Kind(int width, float defaultValue) { this.width = width; this.defaultValue = defaultValue; }
    }
    public record CurveScale(int parameterOffset, float[] defaults) {
        public CurveScale { defaults = defaults.clone(); }
        @Override public float[] defaults() { return defaults.clone(); }
    }
    private final Kind kind;
    private final int current, previous;
    private final StatelessDistribution distribution;
    private final @Nullable CurveScale curveScale;

    public ScaleAttributeModule(Kind kind, int current, int previous, StatelessDistribution distribution, @Nullable CurveScale curveScale) {
        this.kind = Objects.requireNonNull(kind); this.current = current; this.previous = previous;
        this.distribution = Objects.requireNonNull(distribution); this.curveScale = curveScale;
        if (kind == Kind.COLOR ? curveScale != null || previous >= 0 : curveScale == null || curveScale.defaults.length != kind.width)
            throw new IllegalArgumentException("Invalid stateless scale-attribute parameters");
    }

    @Override public void simulate(StatelessParticleSimulation context) {
        float[] scale = context.scratchVector(0), previousScale = context.scratchVector(1), factor = context.scratchVector(2);
        float[] value = context.scratchVector(3), previousValue = context.scratchVector(4);
        if (curveScale != null)
            for (int component = 0; component < kind.width; component++)
                factor[component] = curveScale.parameterOffset >= 0 ? context.dynamicFloat(curveScale.parameterOffset + component) : curveScale.defaults[component];
        for (int row = 0; row < context.count(); row++) {
            read(context, current, row, value);
            if (kind == Kind.COLOR) distribution.sample(context, row, 0, context.normalizedAge(row), scale, kind.width);
            else {
                read(context, previous, row, previousValue);
                distribution.samplePair(context, row, 0, context.normalizedAge(row), context.previousNormalizedAge(row), scale, previousScale, kind.width);
            }
            for (int component = 0; component < kind.width; component++) {
                if (current >= 0) context.particleFloat(current + component, row, scaled(value[component], scale[component], factor, component));
            }
            if (kind != Kind.COLOR && previous >= 0)
                for (int component = 0; component < kind.width; component++)
                    context.particleFloat(previous + component, row, scaled(previousValue[component], previousScale[component], factor, component));
        }
    }

    private void read(StatelessParticleSimulation context, int first, int row, float[] values) {
        for (int component = 0; component < kind.width; component++)
            values[component] = first < 0 ? kind.defaultValue : context.particleFloat(first + component, row);
    }
    private float scaled(float value, float distribution, float[] factor, int component) {
        return switch (kind) {
            case MESH -> distribution * factor[component] * value;
            case RIBBON -> value * factor[component] * distribution;
            // The CPU sprite source reads CurveScale but does not multiply it into the result.
            case COLOR, SPRITE -> value * distribution;
        };
    }
}
