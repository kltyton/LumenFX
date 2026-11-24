package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Native Niagara mip programs, prepared with the emitter's resource generation. */
public record PreparedNiagaraTargetShaders(String engineHead, Map<String, PreparedNiagaraShaders.Stage> mipPrograms) {
    public PreparedNiagaraTargetShaders { mipPrograms = Map.copyOf(mipPrograms); }

    public static PreparedNiagaraTargetShaders prepare(ResourceManager resources) {
        var prepared = NiagaraUtilityShaderResources.prepare(resources, "niagara_render_target_mips",
                Map.of("generate", Set.of("linear", "linear_srgb", "blur", "blur_srgb")));
        return new PreparedNiagaraTargetShaders(prepared.engineHead(), prepared.programs().get("generate"));
    }
}
