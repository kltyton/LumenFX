package com.kltyton.lumenfx.effect.niagara.collision;

import com.google.gson.JsonObject;
import java.util.Objects;

/** The authored allocation and provider selection of an asynchronous GPU trace interface. */
public record NiagaraAsyncGpuTraceDefinition(int maxTracesPerParticle, int maxRetraces, Provider provider) {
    public enum Provider { DEFAULT, HARDWARE_RAY_TRACING, GLOBAL_DISTANCE_FIELD, NONE }

    public NiagaraAsyncGpuTraceDefinition {
        Objects.requireNonNull(provider);
        if (maxTracesPerParticle < 0 || maxRetraces < 0)
            throw new IllegalArgumentException("Negative Niagara async GPU trace capacity or retrace count");
    }

    public static NiagaraAsyncGpuTraceDefinition read(JsonObject definition) {
        var provider = switch (definition.get("provider").getAsInt()) {
            case 0 -> Provider.DEFAULT;
            case 1 -> Provider.HARDWARE_RAY_TRACING;
            case 2 -> Provider.GLOBAL_DISTANCE_FIELD;
            case 3 -> Provider.NONE;
            default -> throw new IllegalArgumentException("Invalid Niagara async GPU trace provider");
        };
        return new NiagaraAsyncGpuTraceDefinition(definition.get("max_traces_per_particle").getAsInt(),
                definition.get("max_retraces").getAsInt(), provider);
    }
}
