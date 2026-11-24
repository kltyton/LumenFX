package com.kltyton.lumenfx.client.resource.shader;

import java.util.Map;
import java.util.Set;
import net.minecraft.server.packs.resources.ResourceManager;

public record PreparedNiagaraNaniteShaders(String engineHead, PreparedNiagaraShaders.Stage instanceScene,
                                            PreparedNiagaraShaders.Stage hierarchyCull,
                                            PreparedNiagaraShaders.Stage hierarchyMain, PreparedNiagaraShaders.Stage hierarchyPost,
                                            PreparedNiagaraShaders.Stage rasterCount, PreparedNiagaraShaders.Stage rasterAllocate,
                                            PreparedNiagaraShaders.Stage rasterScatter, PreparedNiagaraShaders.Stage hardwarePrepare,
                                            PreparedNiagaraShaders.Stage softwareDepth, PreparedNiagaraShaders.Stage softwarePrimitives,
                                            PreparedNiagaraShaders.Stage shadingGeometry, PreparedNiagaraShaders.Stage vertexPassthrough) {
    public static PreparedNiagaraNaniteShaders prepare(ResourceManager resources) {
        Set<String> variant = Set.of("default");
        var shaders = NiagaraUtilityShaderResources.prepare(resources, "niagara_nanite_scene_v3",
                Map.ofEntries(Map.entry("instance_scene", variant),
                        Map.entry("hierarchy_cull", variant), Map.entry("hierarchy_main", variant), Map.entry("hierarchy_post", variant),
                        Map.entry("raster_count", variant), Map.entry("raster_allocate", variant), Map.entry("raster_scatter", variant),
                        Map.entry("hardware_prepare", variant), Map.entry("software_depth", variant), Map.entry("software_primitives", variant),
                        Map.entry("shading_geometry", variant), Map.entry("vertex_passthrough", variant)));
        var programs = shaders.programs();
        return new PreparedNiagaraNaniteShaders(shaders.engineHead(), programs.get("instance_scene").get("default"),
                programs.get("hierarchy_cull").get("default"),
                programs.get("hierarchy_main").get("default"), programs.get("hierarchy_post").get("default"),
                programs.get("raster_count").get("default"), programs.get("raster_allocate").get("default"),
                programs.get("raster_scatter").get("default"), programs.get("hardware_prepare").get("default"),
                programs.get("software_depth").get("default"), programs.get("software_primitives").get("default"),
                programs.get("shading_geometry").get("default"), programs.get("vertex_passthrough").get("default"));
    }
}
