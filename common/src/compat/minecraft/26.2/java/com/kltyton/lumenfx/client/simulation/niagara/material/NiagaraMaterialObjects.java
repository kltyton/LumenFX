package com.kltyton.lumenfx.client.simulation.niagara.material;

import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** Owns the shared loaded MID objects of one resource generation. */
public final class NiagaraMaterialObjects {
    private final Map<String, DynamicMaterialValues> instances = new ConcurrentHashMap<>();

    public @Nullable DynamicMaterialValues find(String source) {
        return instances.get(SourceNames.canonical(source));
    }

    public DynamicMaterialValues load(String source) {
        String identity = SourceNames.canonical(source);
        if (identity.isBlank() || identity.equals("none")) throw new IllegalArgumentException("Missing source material instance path");
        return instances.computeIfAbsent(identity, ignored -> new DynamicMaterialValues());
    }
}
