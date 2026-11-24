package com.kltyton.lumenfx.client.resource.shader;

import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Immutable offline program prepared before the render-thread resource transaction. */
public record PreparedFogIntegration(ShaderProgram shader) {
    public PreparedFogIntegration { Objects.requireNonNull(shader, "shader"); }

    public static PreparedFogIntegration prepare(ResourceManager resources) {
        Identifier path = Identifier.fromNamespaceAndPath("lumenfx", "shaders/compute/fog/integrate/program.json");
        try {
            var metadata = ShaderResourceFiles.json(resources, path);
            ShaderResourceFiles.require("lumenfx.fog_integration.v1", metadata.get("schema").getAsString(), path.toString());
            var artifacts = metadata.getAsJsonObject("artifacts");
            byte[] vulkan = ShaderResourceFiles.artifact(resources, path, artifacts.getAsJsonObject("vulkan"));
            byte[] opengl = ShaderResourceFiles.artifact(resources, path, artifacts.getAsJsonObject("opengl"));
            return new PreparedFogIntegration(ShaderProgramDecoder.decodeStage("VolumetricFogIntegrate",
                    metadata.getAsJsonObject("shader"), vulkan, opengl));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare volume integration shader " + path, error);
        }
    }
}
