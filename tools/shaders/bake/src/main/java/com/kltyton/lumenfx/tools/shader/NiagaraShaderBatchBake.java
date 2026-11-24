package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Produces saved GPU stages for every Niagara effect in a resource tree. */
public final class NiagaraShaderBatchBake {
    private NiagaraShaderBatchBake() { }

    public static void main(String[] arguments) throws IOException {
        Path resourceRoot = null;
        List<String> baseArguments = new ArrayList<>();
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].equals("--resource-root")) {
                if (resourceRoot != null || ++i >= arguments.length) throw new BakeException("Invalid --resource-root");
                resourceRoot = Path.of(arguments[i]).toAbsolutePath().normalize();
            } else baseArguments.add(arguments[i]);
        }
        if (resourceRoot == null || !Files.isDirectory(resourceRoot.resolve("assets")))
            throw new BakeException("Resource root must contain an assets directory");
        BakeOptions base = BakeOptions.parse(baseArguments.toArray(String[]::new));
        if (base.niagaraEffect() != null || base.generatedNiagaraSource() != null)
            throw new BakeException("Batch production selects its own effect and shader source");
        Path temporary = base.workDirectory().resolve("tmp");
        Files.createDirectories(temporary);
        System.setProperty("java.io.tmpdir", temporary.toString());
        Path assets = resourceRoot.resolve("assets");
        Set<String> pipelines = new HashSet<>();
        try (var namespaces = Files.list(assets)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).sorted().toList()) {
                Path effects = namespace.resolve("lumenfx/effects");
                if (!Files.isDirectory(effects)) continue;
                try (var paths = Files.walk(effects)) {
                    for (Path effect : paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        JsonObject root = JsonParser.parseString(Files.readString(effect, StandardCharsets.UTF_8)).getAsJsonObject();
                        JsonObject niagara = root.getAsJsonObject("niagara");
                        JsonArray emitters = niagara == null ? null : niagara.getAsJsonArray("emitters");
                        if (emitters == null) continue;
                        for (JsonElement entry : emitters) {
                            JsonObject emitter = entry.getAsJsonObject();
                            JsonObject gpu = emitter.getAsJsonObject("gpu_program");
                            if (gpu == null) continue;
                            String emitterName = emitter.get("name").getAsString();
                            String pipeline = gpu.get("shader_pipeline").getAsString();
                            String identifier = gpu.get("hlsl_source").getAsString();
                            if (!pipeline.matches("niagara_[0-9a-f]{64}")
                                    || !identifier.matches("[a-z0-9_]+:[a-z0-9_./-]+") || identifier.contains(".."))
                                throw new BakeException("Invalid Niagara GPU resource in " + effect);
                            String[] parts = identifier.split(":", 2);
                            Path source = assets.resolve(parts[0]).resolve(parts[1]).normalize();
                            if (!source.startsWith(assets.resolve(parts[0])) || !Files.isRegularFile(source))
                                throw new BakeException("Missing Niagara GPU source " + identifier);
                            if (!pipelines.add(parts[0] + ":" + pipeline))
                                throw new BakeException("Duplicate Niagara GPU pipeline " + parts[0] + ":" + pipeline);
                            Path output = assets.resolve(parts[0]).resolve("shaders/compute");
                            BakeOptions options = new BakeOptions(base.ueRoot(), base.dxc(), output, base.workDirectory(),
                                    base.manifest(), base.compatDirectory(), base.sceneColorFormat(), base.sceneDepthFormat(),
                                    base.velocityFormat(), base.includeOptional(), base.keepWork(), base.dxcTimeout(),
                                    source, base.generatedIncludeDirectory(), base.shaderDirectories(), effect, emitterName);
                            StageBake.run(options, pipeline);
                        }
                    }
                }
            }
        }
    }
}
