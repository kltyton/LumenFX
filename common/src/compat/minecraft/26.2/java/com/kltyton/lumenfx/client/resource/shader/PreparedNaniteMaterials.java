package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import java.io.IOException;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** Immutable prepared Nanite material programs, indexed by their original material shader identifier. */
public record PreparedNaniteMaterials(Map<Identifier, PreparedNaniteVertexMaterial> vertex,
                                      Map<Identifier, ShaderProgram> raster) {
    public static final PreparedNaniteMaterials EMPTY = new PreparedNaniteMaterials(Map.of(), Map.of());

    public PreparedNaniteMaterials {
        vertex = Map.copyOf(vertex);
        raster = Map.copyOf(raster);
    }

    /** Returns null for legacy materials with neither a raster source marker nor a baked raster program. */
    public static @Nullable ShaderProgram prepareRaster(ResourceManager resources, Identifier materialShader) throws IOException {
        String basePath = "shaders/" + materialShader.getPath() + "_nanite_raster";
        Identifier source = Identifier.fromNamespaceAndPath(materialShader.getNamespace(), basePath + ".nanite_raster.json");
        Identifier metadata = Identifier.fromNamespaceAndPath(materialShader.getNamespace(), basePath + ".nanite.program.json");
        boolean hasSource = resources.getResource(source).isPresent();
        if (!hasSource && resources.getResource(metadata).isEmpty()) return null;
        if (hasSource) {
            JsonObject marker = ShaderResourceFiles.json(resources, source);
            ShaderResourceFiles.require("lumenfx.nanite_raster_source.v1", marker.get("schema").getAsString(), source.toString());
        }
        JsonObject data = ShaderResourceFiles.json(resources, metadata);
        ShaderResourceFiles.require("lumenfx.nanite_raster_program.v2", data.get("schema").getAsString(), metadata.toString());
        JsonObject artifacts = data.getAsJsonObject("artifacts");
        byte[] vulkan = ShaderResourceFiles.artifact(resources, metadata, artifacts.getAsJsonObject("vulkan"));
        byte[] opengl = ShaderResourceFiles.artifact(resources, metadata, artifacts.getAsJsonObject("opengl"));
        var shader = ShaderProgramDecoder.decodeStage(materialShader.toString() + "/nanite_raster",
                data.getAsJsonObject("shader"), vulkan, opengl);
        if (shader.workgroupX() != 64 || shader.workgroupY() != 1 || shader.workgroupZ() != 1)
            throw new IllegalArgumentException("Nanite raster material requires a 64×1×1 workgroup: " + metadata);
        return shader;
    }
}
