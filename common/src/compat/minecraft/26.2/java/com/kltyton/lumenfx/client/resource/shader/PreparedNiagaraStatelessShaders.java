package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** The analytic default template shares one native compute shader across all emitter instances. */
public record PreparedNiagaraStatelessShaders(String engineHead, PreparedNiagaraShaders.Stage simulation) {
    public static PreparedNiagaraStatelessShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "niagara_stateless", Map.of("simulate", Set.of("default")));
        return new PreparedNiagaraStatelessShaders(bundle.engineHead(), bundle.programs().get("simulate").get("default"));
    }
}
