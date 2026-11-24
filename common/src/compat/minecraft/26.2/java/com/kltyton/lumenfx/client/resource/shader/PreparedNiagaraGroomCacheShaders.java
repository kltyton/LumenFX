package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

public record PreparedNiagaraGroomCacheShaders(String engineHead, Map<String, PreparedNiagaraShaders.Stage> interpolation) {
    static PreparedNiagaraGroomCacheShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "niagara_groom_cache",
                Map.of("interpolate", Set.of("local", "world")));
        return new PreparedNiagaraGroomCacheShaders(bundle.engineHead(), bundle.programs().get("interpolate"));
    }
}
