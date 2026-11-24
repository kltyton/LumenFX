package com.kltyton.lumenfx.effect.niagara.parameter.store;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Full variable identities keep numeric, interface and UObject slots distinct. */
public record NiagaraParameterLayout(List<Variable> variables, int numericBytes, int interfaceCount, int objectCount) {
    public NiagaraParameterLayout(List<Variable> variables, int numericBytes) { this(variables, numericBytes, -1, -1); }
    public NiagaraParameterLayout(List<Variable> variables) { this(variables, -1); }
    public enum Storage { NUMERIC, INTERFACE, OBJECT }
    public record Key(String name, String sourceType, int underlyingType, int flags, Storage storage) {
        public Key {
            Objects.requireNonNull(name);
            Objects.requireNonNull(sourceType);
            Objects.requireNonNull(storage);
            if (underlyingType < 1 || underlyingType > 3 || flags < 0 || flags > 255
                    || (storage != Storage.NUMERIC) != (underlyingType == 1))
                throw new IllegalArgumentException("Invalid Niagara parameter type identity");
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && underlyingType == key.underlyingType && flags == key.flags
                    && storage == key.storage && SourceNames.equal(name, key.name) && SourceNames.equal(sourceType, key.sourceType);
        }
        @Override public int hashCode() {
            return Objects.hash(SourceNames.canonical(name), SourceNames.canonical(sourceType), underlyingType, flags, storage);
        }
        public String storeTypeName() {
            int separator = Math.max(sourceType.lastIndexOf('.'), Math.max(sourceType.lastIndexOf('/'), sourceType.lastIndexOf(':')));
            return sourceType.substring(separator + 1);
        }
    }
    public record Variable(Key key, int bytes, @Nullable Boolean lwc, int offset, int objectIndex) {
        public Variable(Key key, int bytes, @Nullable Boolean lwc, int offset) { this(key, bytes, lwc, offset, -1); }
        public Variable(Key key, int bytes) { this(key, bytes, null); }
        public Variable(Key key, int bytes, @Nullable Boolean lwc) { this(key, bytes, lwc, -1); }
        public Variable {
            Objects.requireNonNull(key);
            if (bytes < 0 || offset < -1 || objectIndex < -1 || key.storage() == Storage.NUMERIC && objectIndex != -1
                    || key.storage() != Storage.NUMERIC && (bytes != 0 || offset != -1))
                throw new IllegalArgumentException("Invalid Niagara parameter slot width");
            if (lwc == null) lwc = builtinLwc(key);
        }
    }
    private static @Nullable Boolean builtinLwc(Key key) {
        if (key.underlyingType() != 2) return false;
        return switch (SourceNames.canonical(key.sourceType())) {
            case "/script/niagara.niagaradouble", "/script/coreuobject.vector2d", "/script/coreuobject.vector",
                    "/script/coreuobject.vector3d", "/script/coreuobject.vector4", "/script/coreuobject.vector4d",
                    "/script/coreuobject.quat", "/script/coreuobject.quat4d" -> true;
            case "/script/niagara.niagarafloat", "/script/niagara.niagaraint32", "/script/niagara.niagarabool",
                    "/script/niagara.niagaraposition", "/script/niagara.niagaraid", "/script/niagara.niagaramatrix",
                    "/script/niagara.niagarahalf", "/script/niagara.niagarahalfvector2", "/script/niagara.niagarahalfvector3",
                    "/script/niagara.niagarahalfvector4", "/script/coreuobject.vector2f", "/script/coreuobject.vector3f",
                    "/script/coreuobject.vector4f", "/script/coreuobject.quat4f", "/script/coreuobject.linearcolor" -> false;
            default -> null;
        };
    }
    public NiagaraParameterLayout {
        variables = List.copyOf(variables);
        if (numericBytes < -1 || interfaceCount < -1 || objectCount < -1)
            throw new IllegalArgumentException("Invalid parameter data capacity");
        var keys = new HashSet<Key>();
        for (var variable : variables)
            if (!keys.add(variable.key())) throw new IllegalArgumentException("Duplicate Niagara parameter slot: " + variable.key().name());
        for (var variable : variables) {
            int count = variable.key().storage() == Storage.INTERFACE ? interfaceCount : objectCount;
            if (variable.objectIndex() >= 0 && count >= 0 && variable.objectIndex() >= count)
                throw new IllegalArgumentException("Parameter object index exceeds its source array: " + variable.key().name());
        }
    }
}
