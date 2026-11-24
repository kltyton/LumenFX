package com.kltyton.lumenfx.effect.niagara.parameter;

import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Numeric values saved by the emitter's compiled RendererBindings store. */
public final class RendererParameterDefaults {
    public record ExternalObject(NiagaraParameterLayout.Key target, String external) {
        public ExternalObject {
            Objects.requireNonNull(external);
            if (target.storage() != NiagaraParameterLayout.Storage.OBJECT) throw new IllegalArgumentException("External renderer mapping requires an object slot");
        }
    }
    public record Variable(String name, String sourceType, int offset, int flags, int underlyingType, int bytes, @Nullable Boolean lwc) {
        public Variable(String name, String sourceType, int offset, int flags, int underlyingType, int bytes) {
            this(name, sourceType, offset, flags, underlyingType, bytes, null);
        }
        public Variable(String name, String sourceType, int offset, int flags) { this(name, sourceType, offset, flags, 2, -1); }
        public Variable {
            Objects.requireNonNull(name);
            Objects.requireNonNull(sourceType);
            if (offset < 0 || bytes < -1 || underlyingType < 2 || underlyingType > 3)
                throw new IllegalArgumentException("Invalid renderer numeric parameter layout");
        }
    }

    public static final RendererParameterDefaults EMPTY = new RendererParameterDefaults(new byte[0], List.of());
    private final ByteBuffer data;
    private final List<Variable> variables;
    private final @Nullable NiagaraParameterLayout required;
    private final @Nullable NiagaraParameterLayout compiled;
    private final List<ExternalObject> externalObjects;

    public RendererParameterDefaults(byte[] bytes, List<Variable> variables) {
        this(bytes, variables, null);
    }
    public RendererParameterDefaults(byte[] bytes, List<Variable> variables, @Nullable NiagaraParameterLayout required) {
        this(bytes, variables, required, null);
    }
    public RendererParameterDefaults(byte[] bytes, List<Variable> variables, @Nullable NiagaraParameterLayout required,
                                     @Nullable NiagaraParameterLayout compiled) {
        this(bytes, variables, required, compiled, List.of());
    }
    public RendererParameterDefaults(byte[] bytes, List<Variable> variables, @Nullable NiagaraParameterLayout required,
                                     @Nullable NiagaraParameterLayout compiled, List<ExternalObject> externalObjects) {
        data = ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        this.variables = List.copyOf(variables);
        this.required = required;
        this.compiled = compiled;
        this.externalObjects = List.copyOf(externalObjects);
        for (var variable : variables)
            if (variable.offset() > bytes.length || variable.bytes() >= 0 && variable.offset() > bytes.length - variable.bytes())
                throw new IllegalArgumentException("Renderer parameter is outside its compiled store");
    }

    public List<Variable> variables() { return variables; }
    public @Nullable NiagaraParameterLayout requiredLayout() { return required; }
    public @Nullable NiagaraParameterLayout compiledLayout() { return compiled; }
    public List<ExternalObject> externalObjects() { return externalObjects; }
    public byte[] dataBytes() {
        byte[] result = new byte[data.limit()];
        data.duplicate().get(result);
        return result;
    }

    public boolean read(String name, MaterialInstanceParameters.Source source, float[] destination) {
        String type = source.sourceType();
        if (type == null) return false;
        if (destination.length != source.components()) throw new IllegalArgumentException("Renderer numeric reader width mismatch");
        return floating(name, type, destination);
    }

    public boolean floating(@Nullable String name, @Nullable String sourceType, float[] destination) {
        if (name == null || SourceNames.equal(name, "None")) return false;
        int bytes = Math.multiplyExact(destination.length, Float.BYTES);
        for (var variable : variables) {
            if (!SourceNames.equal(variable.name(), name)) continue;
            if (sourceType != null && !SourceNames.equal(variable.sourceType(), sourceType)) continue;
            if (variable.bytes() >= 0 && variable.bytes() != bytes) continue;
            if (variable.offset() > data.limit() - bytes)
                throw new IllegalArgumentException("Renderer numeric parameter exceeds its compiled store: " + name);
            for (int index = 0; index < destination.length; index++)
                destination[index] = data.getFloat(variable.offset() + index * Float.BYTES);
            return true;
        }
        return false;
    }

    public @Nullable Integer integer(@Nullable String name, boolean bool) {
        if (name == null || SourceNames.equal(name, "None")) return null;
        String type = bool ? "/Script/Niagara.NiagaraBool" : "/Script/Niagara.NiagaraInt32";
        for (var variable : variables) {
            if (!SourceNames.equal(variable.name(), name) || !SourceNames.equal(variable.sourceType(), type)) continue;
            if (variable.offset() > data.limit() - Integer.BYTES)
                throw new IllegalArgumentException("Renderer integer parameter exceeds its compiled store: " + name);
            return data.getInt(variable.offset());
        }
        return null;
    }
}
