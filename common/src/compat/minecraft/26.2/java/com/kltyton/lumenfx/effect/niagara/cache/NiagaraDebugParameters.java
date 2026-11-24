package com.kltyton.lumenfx.effect.niagara.cache;

import java.util.List;

/** Original emitter handle names and static script values used by cache recordings. */
public record NiagaraDebugParameters(List<String> emitterNames, List<NiagaraCacheDebugData.Parameter> staticValues) {
    public NiagaraDebugParameters {
        emitterNames = List.copyOf(emitterNames);
        staticValues = List.copyOf(staticValues);
    }
}
