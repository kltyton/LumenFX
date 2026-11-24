package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

/** Native mesh SRT preparation and GPUScene transform encoding programs. */
public record PreparedNiagaraMeshSceneShaders(String engineHead, Map<String, PreparedNiagaraShaders.Stage> variants,
                                               Map<String, PreparedNiagaraShaders.Stage> compaction,
                                               PreparedNiagaraShaders.Stage culling) {
    public PreparedNiagaraMeshSceneShaders { variants = Map.copyOf(variants); compaction = Map.copyOf(compaction); }

    public static PreparedNiagaraMeshSceneShaders prepare(ResourceManager resources) {
        var bundle = NiagaraUtilityShaderResources.prepare(resources, "niagara_mesh_scene",
                Map.of("prepare", Set.of("cpu", "gpu"), "compaction_offsets", Set.of("default"),
                        "compaction_scatter", Set.of("default"), "cull", Set.of("default")));
        return new PreparedNiagaraMeshSceneShaders(bundle.engineHead(), bundle.programs().get("prepare"),
                Map.of("compaction_offsets", bundle.programs().get("compaction_offsets").get("default"),
                        "compaction_scatter", bundle.programs().get("compaction_scatter").get("default")),
                bundle.programs().get("cull").get("default"));
    }
}
