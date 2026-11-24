package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resource-prepared Niagara persistent ID allocation and free-list programs. */
public record PreparedNiagaraIdShaders(String engineHead, Map<String, Map<String, PreparedNiagaraShaders.Stage>> stages) {
    public PreparedNiagaraIdShaders { stages = Map.copyOf(stages); }

    public static PreparedNiagaraIdShaders prepare(ResourceManager resources) {
        var prepared = NiagaraUtilityShaderResources.prepare(resources, "niagara_persistent_ids",
                Map.of("init", Set.of("default"), "compact", Set.of("default"), "rebase", Set.of("default"),
                        "dispatch_indirect", Set.of("default")));
        return new PreparedNiagaraIdShaders(prepared.engineHead(), prepared.programs());
    }
}
