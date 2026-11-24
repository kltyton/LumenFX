package com.kltyton.lumenfx.effect.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Source state settings and immutable snapshots of the live parameter stores. */
public record NiagaraCacheDebugData(NiagaraSystemState systemState, List<Frame> frames) {
    public NiagaraCacheDebugData { frames = List.copyOf(frames); }

    public record Frame(Map<String, List<Parameter>> stores) {
        public Frame {
            var copy = new LinkedHashMap<String, List<Parameter>>();
            stores.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            stores = Map.copyOf(copy);
        }
    }

    public record Reference(String identity, String type) { }

    public record Parameter(Key key, byte[] numeric, @Nullable Reference reference) {
        public Parameter {
            if (key.storage() == Storage.NUMERIC ? reference != null : numeric.length != 0)
                throw new IllegalArgumentException("Niagara debug parameter does not match its storage type");
            numeric = numeric.clone();
        }
        @Override public byte[] numeric() { return numeric.clone(); }
    }
}
