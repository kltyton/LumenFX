package com.kltyton.lumenfx.effect.niagara.export;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.UserParameterNames;

/** Authored particle-export handler and GPU allocation settings. */
public record NiagaraParticleExportDefinition(String parameter, boolean perParticle, int fixedCount, float multiplier) {
    public NiagaraParticleExportDefinition {
        parameter = SourceNames.canonical(UserParameterNames.display(parameter));
        if (fixedCount < 0 || !Float.isFinite(multiplier) || multiplier < 0)
            throw new IllegalArgumentException("Invalid Niagara particle export allocation");
    }

    public static NiagaraParticleExportDefinition read(JsonObject data) {
        return new NiagaraParticleExportDefinition(data.get("parameter").getAsString(), data.get("per_particle").getAsBoolean(),
                data.get("fixed_count").getAsInt(), data.get("multiplier").getAsFloat());
    }
}
