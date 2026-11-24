package com.kltyton.lumenfx.client.simulation.niagara.renderer;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Dataset accessors and renderer-bound host defaults share the source binding namespace. */
public final class NiagaraRendererFields {
    private NiagaraRendererFields() { }

    public record Inputs(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                         EffectHandle.Input input, @Nullable NiagaraParameterStore store) {
        public Inputs(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input) {
            this(system, variables, input, null);
        }
    }

    public static final class Floating {
        private final int component, type;
        private final @Nullable String host;
        private final float[] defaults;

        public Floating(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
                        String semantic, String fallback, String valueType) {
            var binding = renderer.attributeBindings().get(semantic);
            String name = binding == null ? fallback : binding.name();
            host = renderer.sourceMode().equals("Emitter") || binding != null && !binding.particle() ? name : null;
            var attribute = host == null ? NiagaraParticleReadTypes.renderer(attributes, name, valueType) : null;
            component = attribute == null ? -1 : attribute.component();
            type = attribute == null ? -1 : attribute.type();
            defaults = new float[NiagaraParticleReadTypes.width(valueType)];
        }

        public boolean hasParticleData() { return component >= 0; }
        public int registerIndex() { return component < 0 ? -1 : component | (type == 2 ? Integer.MIN_VALUE : 0); }

        public void defaults(float value, Inputs inputs) {
            Arrays.fill(defaults, value);
            bind(inputs);
        }

        public void defaults(float[] values, Inputs inputs) {
            if (values.length != defaults.length) throw new IllegalArgumentException("Renderer default component count differs");
            System.arraycopy(values, 0, defaults, 0, values.length);
            bind(inputs);
        }

        public void copyDefaults(float[] output) {
            System.arraycopy(defaults, 0, output, 0, Math.min(defaults.length, output.length));
        }

        private void bind(Inputs inputs) {
            NiagaraRendererParameters.floating(host, inputs.system(), inputs.variables(), inputs.input(), inputs.store(), defaults);
        }

        public float read(@Nullable VectorDataSet data, int row, int axis) {
            if (component < 0) return defaults[axis];
            if (data == null) throw new IllegalStateException("Particle renderer binding has no CPU dataset");
            return type == 2 ? data.half(component + axis, row) : data.floating(component + axis, row);
        }
    }

    public static final class IntegerField {
        private final int component;
        private final @Nullable String host;
        private final boolean bool;

        public IntegerField(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.Renderer renderer,
                            String semantic, String fallback, boolean bool) {
            this(attributes, renderer.attributeBindings().get(semantic), fallback, renderer.sourceMode().equals("Emitter"), bool);
        }

        private IntegerField(List<NiagaraDefinition.Attribute> attributes, NiagaraDefinition.@Nullable RendererAttributeBinding binding,
                             String fallback, boolean emitter, boolean bool) {
            this(attributes, binding == null ? fallback : binding.name(), emitter || binding != null && !binding.particle(), bool);
        }

        public IntegerField(List<NiagaraDefinition.Attribute> attributes, String name, boolean hostBinding, boolean bool) {
            var attribute = hostBinding ? null : NiagaraParticleReadTypes.renderer(attributes, name, bool ? "Bool" : "Int");
            component = attribute == null ? -1 : attribute.component();
            host = hostBinding ? name : null;
            this.bool = bool;
        }

        public boolean hasParticleData() { return component >= 0; }
        public int registerIndex() { return component; }

        public int defaultValue(int value, Inputs inputs) {
            Integer bound = NiagaraRendererParameters.integer(host, bool, inputs.system(), inputs.variables(), inputs.input(), inputs.store());
            return bound == null ? value : bound;
        }

        public int read(@Nullable VectorDataSet data, int row, int fallback) {
            if (component < 0) return fallback;
            if (data == null) throw new IllegalStateException("Particle renderer binding has no CPU dataset");
            return data.integer(component, row);
        }
    }

}
