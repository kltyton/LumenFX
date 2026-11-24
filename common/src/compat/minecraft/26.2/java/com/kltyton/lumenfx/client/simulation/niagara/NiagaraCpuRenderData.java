package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Borrowed CPU render data, consumed after workers finish and before the next simulation update. */
public record NiagaraCpuRenderData(long systemIdentity, NiagaraDefinition.Emitter emitter,
                                    NiagaraDefinition.Renderer renderer, VectorDataSet particles,
                                    VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                    double originX, double originY, double originZ,
                                    @Nullable NiagaraParameterStore rendererStore) {
    public NiagaraCpuRenderData(long systemIdentity, NiagaraDefinition.Emitter emitter,
                                NiagaraDefinition.Renderer renderer, VectorDataSet particles,
                                VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                double originX, double originY, double originZ) {
        this(systemIdentity, emitter, renderer, particles, system, variables, originX, originY, originZ, null);
    }
}
