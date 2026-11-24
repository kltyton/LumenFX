package com.kltyton.lumenfx.effect.niagara.cache;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public record NiagaraSimCacheReaderDefinition(@Nullable Identifier asset, String parameter, String emitter) {
    public NiagaraSimCacheReaderDefinition {
        parameter = SourceNames.canonical(parameter);
        emitter = SourceNames.canonical(emitter);
    }

    public static NiagaraSimCacheReaderDefinition read(JsonObject source) {
        return new NiagaraSimCacheReaderDefinition(source.has("asset") ? Identifier.parse(source.get("asset").getAsString()) : null,
                source.get("user_parameter").getAsString(), source.get("emitter").getAsString());
    }
}
