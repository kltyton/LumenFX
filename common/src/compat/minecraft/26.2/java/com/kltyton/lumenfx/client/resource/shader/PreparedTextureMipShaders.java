package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Offline RenderCore mip programs, prepared before publishing a texture generation. */
public record PreparedTextureMipShaders(String engineHead, Map<String, PreparedNiagaraShaders.Stage> stages) {
    public PreparedTextureMipShaders { stages = Map.copyOf(stages); }

    public static PreparedTextureMipShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "texture_target_mips",
                Map.of("generate", Set.of("linear", "srgb")));
        return new PreparedTextureMipShaders(bundle.engineHead(), bundle.programs().get("generate"));
    }
}
