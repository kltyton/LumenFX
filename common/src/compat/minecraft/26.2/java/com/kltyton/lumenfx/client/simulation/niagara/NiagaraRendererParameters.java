package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads optional renderer parameters from the current system output, typed stores, or user overrides. */
public final class NiagaraRendererParameters {
    private NiagaraRendererParameters() { }

    public static @Nullable Integer integer(@Nullable String name, boolean bool, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, RendererParameterDefaults defaults) {
        return integer(name, bool, system, variables, input, null, defaults);
    }

    public static @Nullable Integer integer(@Nullable String name, boolean bool, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
            @Nullable NiagaraParameterStore store, RendererParameterDefaults defaults) {
        Integer value = integer(name, bool, system, variables, input, store);
        return value != null ? value : defaults.integer(name, bool);
    }

    public static @Nullable Integer integer(@Nullable String name, boolean bool, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input) {
        return integer(name, bool, system, variables, input, (NiagaraParameterStore) null);
    }

    public static @Nullable Integer integer(@Nullable String name, boolean bool, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
            @Nullable NiagaraParameterStore store) {
        if (name == null || SourceNames.equal(name, "None")) return null;
        var value = parameter(input, name);
        if (value != null) {
            EffectHandle.ParameterType expected = bool ? EffectHandle.ParameterType.BOOL : EffectHandle.ParameterType.INT;
            if (value.type() != expected) throw new IllegalArgumentException("Incompatible Niagara renderer binding " + name);
            return (int) value.words()[0];
        }
        var variable = variable(variables, name);
        if (variable != null) {
            if (variable.intCount() != 1 || variable.floatCount() != 0 || variable.halfCount() != 0)
                throw new IllegalArgumentException("Incompatible Niagara renderer binding " + name);
            if (!variable.sourceType().endsWith(bool ? ".NiagaraBool" : ".NiagaraInt32"))
                throw new IllegalArgumentException("Incompatible Niagara renderer binding type " + name);
            return system.integer(variable.intStart(), 0);
        }
        return store == null ? null : store.readRendererInteger(name, bool);
    }

    public static boolean floating(@Nullable String name, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, float[] output,
            RendererParameterDefaults defaults) {
        return floating(name, system, variables, input, null, output, defaults);
    }

    public static boolean floating(@Nullable String name, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
            @Nullable NiagaraParameterStore store, float[] output, RendererParameterDefaults defaults) {
        return floating(name, system, variables, input, store, output) || defaults.floating(name, null, output);
    }

    public static boolean floating(@Nullable String name, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, float[] output) {
        return floating(name, system, variables, input, (NiagaraParameterStore) null, output);
    }

    public static boolean floating(@Nullable String name, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
            @Nullable NiagaraParameterStore store, float[] output) {
        if (name == null || SourceNames.equal(name, "None")) return false;
        var value = parameter(input, name);
        if (value != null) {
            if (value.type() == EffectHandle.ParameterType.POSITION) {
                var owner = input.pose().position();
                NiagaraPositionParameters.read(value, NiagaraPositionParameters.origin(owner.x * 100),
                        NiagaraPositionParameters.origin(owner.z * 100), NiagaraPositionParameters.origin(owner.y * 100), output);
                return true;
            }
            if (value.type().components() != output.length || value.type().storage() != 0 && value.type().storage() != 3)
                throw new IllegalArgumentException("Incompatible Niagara renderer binding " + name);
            long[] words = value.words();
            for (int i = 0; i < output.length; i++) output[i] = value.type().storage() == 3
                    ? Float.float16ToFloat((short) words[i]) : Float.intBitsToFloat((int) words[i]);
            return true;
        }
        var variable = variable(variables, name);
        if (variable != null) {
            if (variable.floatCount() == output.length && variable.intCount() == 0 && variable.halfCount() == 0) {
                for (int i = 0; i < output.length; i++) output[i] = system.floating(variable.floatStart() + i, 0);
            } else if (variable.halfCount() == output.length && variable.intCount() == 0 && variable.floatCount() == 0) {
                for (int i = 0; i < output.length; i++) output[i] = system.half(variable.halfStart() + i, 0);
            } else throw new IllegalArgumentException("Incompatible Niagara renderer binding " + name);
            return true;
        }
        return store != null && store.readRendererFloating(name, output);
    }

    public static EffectHandle.@Nullable ParameterValue parameter(EffectHandle.Input input, String name) {
        var exact = input.parameters().get(name);
        if (exact != null) return exact;
        for (var entry : input.parameters().entrySet())
            if (SourceNames.equal(entry.getKey(), name)) return entry.getValue();
        return null;
    }

    public static boolean floating(VectorProgram program, byte[] constants, String name,
            MaterialInstanceParameters.Source source, float[] output) {
        String requiredType = source.sourceType();
        if (requiredType == null) return false;
        if (output.length != source.components()) throw new IllegalArgumentException("Renderer script reader width mismatch");
        for (var parameter : program.parameters()) {
            if (!SourceNames.equal(parameter.name(), name) || !SourceNames.equal(parameter.sourceType(), requiredType)) continue;
            int bytes = Math.multiplyExact(output.length, Float.BYTES);
            int offset = Math.addExact(program.runtimeBytes(), parameter.offset());
            if (parameter.bytes() != bytes || offset < 0 || offset > constants.length - bytes)
                throw new IllegalArgumentException("Renderer script parameter is outside its typed buffer: " + name);
            ByteBuffer data = ByteBuffer.wrap(constants).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < output.length; index++) output[index] = data.getFloat(offset + index * Float.BYTES);
            return true;
        }
        return false;
    }

    public static NiagaraDefinition.@Nullable Variable variable(Map<String, NiagaraDefinition.Variable> variables, String name) {
        var exact = variables.get(name);
        if (exact != null) return exact;
        for (var entry : variables.entrySet())
            if (SourceNames.equal(entry.getKey(), name)) return entry.getValue();
        return null;
    }
}
