package com.kltyton.lumenfx.client.simulation.niagara.parameter;

import com.kltyton.lumenfx.api.client.parameter.ParameterCollectionValues;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionInstance;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Objects;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A frame freezes both collection scopes before any emitter worker starts. */
public record NiagaraCollectionSnapshot(ParameterCollectionValues world, ParameterCollectionValues component,
                                        Map<String, MaterialCollectionInstance.Snapshot> materials) {
    public NiagaraCollectionSnapshot(ParameterCollectionValues world, ParameterCollectionValues component) { this(world, component, Map.of()); }
    public static final NiagaraCollectionSnapshot EMPTY = new NiagaraCollectionSnapshot(ParameterCollectionValues.EMPTY, ParameterCollectionValues.EMPTY);
    public NiagaraCollectionSnapshot {
        Objects.requireNonNull(world); Objects.requireNonNull(component);
        Map<String, MaterialCollectionInstance.Snapshot> copy = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        copy.putAll(materials); materials = Collections.unmodifiableMap(copy);
    }
}
