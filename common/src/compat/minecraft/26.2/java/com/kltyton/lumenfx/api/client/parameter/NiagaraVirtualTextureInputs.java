package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Publishes virtual texture objects by native asset path or user parameter name. */
public record NiagaraVirtualTextureInputs(Map<String, NiagaraRuntimeVirtualTexture> runtime,
                                          Map<String, NiagaraStreamingVirtualTexture> streaming) {
    public static final NiagaraVirtualTextureInputs EMPTY = new NiagaraVirtualTextureInputs(Map.of(), Map.of());

    public NiagaraVirtualTextureInputs {
        runtime = names(runtime);
        streaming = names(streaming);
    }

    private static <T> Map<String, T> names(Map<String, T> input) {
        var result = new TreeMap<String, T>(SourceNames.ASCII_CASE_INSENSITIVE);
        input.forEach((name, value) -> {
            if (result.putIfAbsent(name, Objects.requireNonNull(value)) != null)
                throw new IllegalArgumentException("Duplicate Niagara virtual texture source " + name);
        });
        return Collections.unmodifiableMap(result);
    }
}
