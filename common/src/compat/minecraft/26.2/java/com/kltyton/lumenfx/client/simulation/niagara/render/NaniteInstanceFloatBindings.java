package com.kltyton.lumenfx.client.simulation.niagara.render;

import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteRendererSettings;
import java.util.ArrayList;
import java.util.List;

/** Resolves the shared host fallback and particle component of each instance custom float. */
public final class NaniteInstanceFloatBindings {
    public record FloatComponent(int particleRegister, float hostValue) {
        public float value(VectorDataSet particles, int row) {
            if (particleRegister == -1) return hostValue;
            int component = particleRegister & Integer.MAX_VALUE;
            return particleRegister < 0 ? particles.half(component, row) : particles.floating(component, row);
        }
    }

    private NaniteInstanceFloatBindings() { }

    public static List<FloatComponent> resolve(NaniteRendererSettings settings, NiagaraDefinition.Emitter emitter,
                                               NiagaraParameterStore parameters, boolean readParticles) {
        var result = new ArrayList<FloatComponent>(settings.instanceFloats().size());
        for (var binding : settings.instanceFloats()) {
            var key = binding.parameter();
            int register = -1;
            float value = binding.defaultValue();
            if (key != null) {
                if (parameters.contains(key)) {
                    int byteOffset = Math.multiplyExact(binding.component(), Float.BYTES);
                    if (byteOffset > parameters.numericBytes(key) - Float.BYTES)
                        throw new IllegalArgumentException("Nanite instance float outside its parameter: " + key.name());
                    value = Float.intBitsToFloat(parameters.numericWord(parameters.numericOffset(key) + byteOffset));
                }
                if (readParticles && key.name().length() > 10 && SourceNames.equal(key.name().substring(0, 10), "Particles."))
                    register = register(emitter.attributes(), key.name().substring(10), key.sourceType(), binding.component());
            }
            result.add(new FloatComponent(register, value));
        }
        return List.copyOf(result);
    }

    private static int register(List<NiagaraDefinition.Attribute> attributes, String name, String type, int component) {
        for (var attribute : attributes) {
            if (!SourceNames.equal(attribute.sourceType(), type)
                    || !SourceNames.equal(attribute.name(), name) && !SourceNames.equal(attribute.name(), "Particles." + name))
                continue;
            if (component >= attribute.count() || attribute.type() == 1) return -1;
            int offset = Math.addExact(attribute.component(), component);
            return attribute.type() == 2 ? offset | Integer.MIN_VALUE : offset;
        }
        return -1;
    }
}
