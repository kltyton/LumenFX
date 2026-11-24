package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Preloaded Unreal Niagara indirect-draw argument programs. */
public record PreparedNiagaraDrawShaders(String engineHead, Map<String, PreparedNiagaraShaders.Stage> variants) {
    public PreparedNiagaraDrawShaders { variants = Map.copyOf(variants); }

    public static PreparedNiagaraDrawShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "niagara_draw_indirect",
                Map.of("generate", Set.of("separate_reset", "combined_reset")));
        return new PreparedNiagaraDrawShaders(bundle.engineHead(), bundle.programs().get("generate"));
    }
}
