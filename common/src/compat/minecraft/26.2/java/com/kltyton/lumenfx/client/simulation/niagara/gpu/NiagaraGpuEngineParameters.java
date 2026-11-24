package com.kltyton.lumenfx.client.simulation.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraEngineParameters;
import org.jspecify.annotations.Nullable;

/** Current and previous Niagara engine constants in their source component layout. */
public final class NiagaraGpuEngineParameters {
    private final NiagaraEngineParameters parameters;

    public NiagaraGpuEngineParameters(byte[] current, byte[] previous) {
        parameters = new NiagaraEngineParameters(current, previous);
    }

    public @Nullable byte[] value(String name) {
        return parameters.value(name);
    }
}
