package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Loads one offline Niagara utility pipeline and its reflected compute stages. */
final class NiagaraUtilityShaderResources {
    record Bundle(String engineHead, Map<String, Map<String, PreparedNiagaraShaders.Stage>> programs) {
        Bundle {
            Map<String, Map<String, PreparedNiagaraShaders.Stage>> copy = new LinkedHashMap<>();
            programs.forEach((key, value) -> copy.put(key, Map.copyOf(value)));
            programs = Map.copyOf(copy);
        }
    }

    private NiagaraUtilityShaderResources() { }

    static Identifier indexPath(String pipeline) {
        return Identifier.fromNamespaceAndPath("lumenfx", "shaders/compute/" + pipeline + "/index.json");
    }

    static Bundle prepare(ResourceManager resources, String pipeline, Map<String, Set<String>> expected) {
        Identifier indexPath = indexPath(pipeline);
        try {
            JsonObject index = ShaderResourceFiles.json(resources, indexPath);
            require(index, "schema", "lumenfx.shaders.index.v1", indexPath);
            require(index, "pipeline", pipeline, indexPath);
            String head = index.get("engineHead").getAsString();
            Map<String, Map<String, PreparedNiagaraShaders.Stage>> programs = new LinkedHashMap<>();
            for (var item : index.getAsJsonArray("programs")) {
                JsonObject entry = item.getAsJsonObject();
                String pass = entry.get("pass").getAsString(), variant = entry.get("variant").getAsString();
                if (!expected.containsKey(pass) || !expected.get(pass).contains(variant))
                    throw new IOException("Unexpected Niagara shader variant " + pass + "/" + variant);
                Identifier path = ShaderResourceFiles.metadataPath(pipeline, entry.get("path").getAsString());
                JsonObject metadata = ShaderResourceFiles.json(resources, path);
                require(metadata, "schema", "lumenfx.shaders.program.v1", path);
                require(metadata, "pipeline", pipeline, path);
                require(metadata, "engineHead", head, path);
                require(metadata, "pass", pass, path);
                ShaderResourceFiles.require(variant, metadata.getAsJsonObject("variant").get("id").getAsString(), path.toString());
                if (metadata.getAsJsonArray("stages").size() != 1) throw new IOException("Invalid Niagara utility shader stages " + path);
                JsonObject stage = metadata.getAsJsonArray("stages").get(0).getAsJsonObject();
                require(stage, "stage", "compute", path);
                JsonObject artifacts = stage.getAsJsonObject("artifacts");
                byte[] vulkan = ShaderResourceFiles.artifact(resources, path, artifacts.getAsJsonObject("vulkan"));
                byte[] opengl = ShaderResourceFiles.artifact(resources, path, artifacts.getAsJsonObject("opengl"));
                var shader = ShaderProgramDecoder.decodeStage(pipeline + "/" + pass + "/" + variant, stage, vulkan, opengl);
                var variants = programs.computeIfAbsent(pass, ignored -> new LinkedHashMap<>());
                if (variants.putIfAbsent(variant, new PreparedNiagaraShaders.Stage(shader, metadata)) != null)
                    throw new IOException("Duplicate Niagara utility shader " + path);
            }
            if (!programs.keySet().equals(expected.keySet())) throw new IOException("Incomplete Niagara shader passes " + indexPath);
            for (var entry : expected.entrySet())
                if (!programs.get(entry.getKey()).keySet().equals(entry.getValue()))
                    throw new IOException("Incomplete Niagara shader variants for " + entry.getKey());
            return new Bundle(head, programs);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare Niagara shaders " + indexPath, error);
        }
    }

    private static void require(JsonObject value, String field, String expected, Identifier path) throws IOException {
        ShaderResourceFiles.require(expected, value.get(field).getAsString(), path.toString());
    }
}
