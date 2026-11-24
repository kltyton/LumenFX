package com.kltyton.lumenfx.effect.niagara.parameter.store;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable declared instance values retain their raw bytes and any original absolute source position. */
public record NumericStoreDefinition(NiagaraParameterLayout layout, byte[] data, List<Value> values) {
    public record Value(NiagaraParameterLayout.Key key, int offset, int bytes, double @Nullable [] position) {
        public Value {
            Objects.requireNonNull(key);
            if (offset < 0 || bytes < 0 || key.storage() != NiagaraParameterLayout.Storage.NUMERIC
                    || position != null && (position.length != 3 || bytes != 12))
                throw new IllegalArgumentException("Invalid instance numeric default");
            position = position == null ? null : position.clone();
        }
        @Override public double @Nullable [] position() { return position == null ? null : position.clone(); }
    }
    public static final NumericStoreDefinition EMPTY = new NumericStoreDefinition(new NiagaraParameterLayout(List.of()), new byte[0], List.of());
    public NumericStoreDefinition {
        Objects.requireNonNull(layout);
        data = data.clone(); values = List.copyOf(values);
        if (layout.variables().size() != values.size()) throw new IllegalArgumentException("Instance numeric layout differs from its defaults");
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index); var variable = layout.variables().get(index);
            if (!value.key().equals(variable.key()) || value.bytes() != variable.bytes() || value.offset() > data.length - value.bytes())
                throw new IllegalArgumentException("Instance numeric default exceeds its layout");
        }
    }
    @Override public byte[] data() { return data.clone(); }
}
