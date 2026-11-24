package com.kltyton.lumenfx.client.resource.shader;

import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Backend programs and output layout for one Nanite vertex material graph. */
public record PreparedNaniteVertexMaterial(ShaderProgram shader, List<Integer> interpolatorWidths) {
    public PreparedNaniteVertexMaterial {
        interpolatorWidths = List.copyOf(interpolatorWidths);
        for (int width : interpolatorWidths)
            if (width < 1 || width > 4) throw new IllegalArgumentException("Invalid Nanite vertex interpolator width");
    }

    public int wordsPerVertex() { return 5 + interpolatorWidths.size(); }

    public static PreparedNaniteVertexMaterial prepare(ResourceManager resources, Identifier source) throws IOException {
        Identifier metadata = Identifier.fromNamespaceAndPath(source.getNamespace(), "shaders/" + source.getPath() + ".nanite.program.json");
        var data = ShaderResourceFiles.json(resources, metadata);
        ShaderResourceFiles.require("lumenfx.nanite_vertex_program.v1", data.get("schema").getAsString(), metadata.toString());
        var artifacts = data.getAsJsonObject("artifacts");
        byte[] vulkan = ShaderResourceFiles.artifact(resources, metadata, artifacts.getAsJsonObject("vulkan"));
        byte[] opengl = ShaderResourceFiles.artifact(resources, metadata, artifacts.getAsJsonObject("opengl"));
        var widths = new ArrayList<Integer>();
        data.getAsJsonArray("interpolator_widths").forEach(value -> widths.add(value.getAsInt()));
        return new PreparedNaniteVertexMaterial(ShaderProgramDecoder.decodeStage(source.toString(), data.getAsJsonObject("shader"), vulkan, opengl), widths);
    }
}
