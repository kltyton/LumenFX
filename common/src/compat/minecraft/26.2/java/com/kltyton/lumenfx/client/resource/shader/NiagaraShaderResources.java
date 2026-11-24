package com.kltyton.lumenfx.client.resource.shader;

import com.kltyton.lumenfx.effect.niagara.groom.HairStrandsDefinition;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Loads the baked stages belonging to a single Niagara GPU emitter. */
public final class NiagaraShaderResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private NiagaraShaderResources() { }

    public static PreparedNiagaraShaders prepare(ResourceManager resources, String namespace, String pipeline,
                                                 JsonObject program, List<NiagaraDefinition.Attribute> attributes,
                                                 Function<Identifier, VectorFieldVolume> fields) {
        if (!pipeline.matches("niagara_[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid Niagara shader pipeline " + pipeline);
        Identifier indexPath = Identifier.fromNamespaceAndPath(namespace, "shaders/compute/" + pipeline + "/index.json");
        try {
            JsonObject index = ShaderResourceFiles.json(resources, indexPath);
            require(index, "schema", "lumenfx.shaders.index.v1", indexPath);
            require(index, "pipeline", pipeline, indexPath);
            String engineHead = index.get("engineHead").getAsString();
            Map<Integer, Map<String, PreparedNiagaraShaders.Stage>> stages = new LinkedHashMap<>();
            for (var item : index.getAsJsonArray("programs")) {
                JsonObject entry = item.getAsJsonObject();
                String pass = entry.get("pass").getAsString();
                String variant = entry.get("variant").getAsString();
                String relative = entry.get("path").getAsString();
                if (!relative.startsWith(pipeline + "/") || relative.contains("..") || relative.contains(":"))
                    throw new IOException("Niagara shader metadata escapes pipeline: " + relative);
                Identifier path = Identifier.fromNamespaceAndPath(namespace, "shaders/compute/" + relative);
                JsonObject metadata = ShaderResourceFiles.json(resources, path);
                require(metadata, "schema", "lumenfx.shaders.program.v1", path);
                require(metadata, "pipeline", pipeline, path);
                require(metadata, "engineHead", engineHead, path);
                require(metadata, "pass", pass, path);
                ShaderResourceFiles.require(variant, metadata.getAsJsonObject("variant").get("id").getAsString(), path.toString());
                int stageIndex = metadata.getAsJsonObject("niagaraStage").get("stage_index").getAsInt();
                if (stageIndex < 0 || !pass.equals("stage_" + stageIndex) || metadata.getAsJsonArray("stages").size() != 1)
                    throw new IOException("Invalid Niagara stage metadata " + path);
                JsonObject stage = metadata.getAsJsonArray("stages").get(0).getAsJsonObject();
                require(stage, "stage", "compute", path);
                JsonObject artifacts = stage.getAsJsonObject("artifacts");
                byte[] vulkan = ShaderResourceFiles.artifact(resources, path, artifacts.getAsJsonObject("vulkan"));
                byte[] opengl = ShaderResourceFiles.artifact(resources, path, artifacts.getAsJsonObject("opengl"));
                var shader = ShaderProgramDecoder.decodeStage(pipeline + "/" + pass + "/" + variant, stage, vulkan, opengl);
                var variants = stages.computeIfAbsent(stageIndex, ignored -> new LinkedHashMap<>());
                if (variants.putIfAbsent(variant, new PreparedNiagaraShaders.Stage(shader, metadata)) != null)
                    throw new IOException("Duplicate Niagara shader variant " + path);
            }
            if (stages.isEmpty()) throw new IOException("Niagara shader index has no stages " + indexPath);
            NiagaraGpuStageSchedule schedule = new NiagaraGpuStageSchedule(program, attributes);
            if (!schedule.validEditorLoops()) LOGGER.error("Invalid Niagara GPU simulation loops in {}; using the source linear order", indexPath);
            PreparedNiagaraTargetShaders targets = null;
            for (var item : program.getAsJsonArray("interfaces")) {
                if ("NiagaraDataInterfaceRenderTarget2D".equals(item.getAsJsonObject().get("class").getAsString())) {
                    targets = PreparedNiagaraTargetShaders.prepare(resources);
                    if (!NiagaraEmitterShaderAbi.matchesUtility(engineHead, targets.engineHead()))
                        throw new IOException("Niagara target shader engine version differs");
                    break;
                }
            }
            var traceShaders = PreparedNiagaraAsyncTraceShaders.required(program)
                    ? PreparedNiagaraAsyncTraceShaders.prepare(resources) : null;
            if (traceShaders != null && !NiagaraEmitterShaderAbi.matchesUtility(engineHead, traceShaders.engineHead()))
                throw new IOException("Niagara asynchronous GPU trace shader engine version differs");
            var fieldShaders = PreparedNiagaraPhysicsFieldShaders.required(program)
                    ? PreparedNiagaraPhysicsFieldShaders.prepare(resources) : null;
            if (fieldShaders != null && !NiagaraEmitterShaderAbi.matchesUtility(engineHead, fieldShaders.engineHead()))
                throw new IOException("Niagara Chaos field shader engine version differs");
            var groomShaders = HairStrandsDefinition.gpuUsesHairStrands(program)
                    ? PreparedNiagaraGroomCacheShaders.prepare(resources) : null;
            if (groomShaders != null && !NiagaraEmitterShaderAbi.matchesUtility(engineHead, groomShaders.engineHead()))
                throw new IOException("Niagara groom cache shader engine version differs");
            return new PreparedNiagaraShaders(engineHead, stages, PreparedNiagaraCurves.read(program),
                    new PreparedNiagaraSpriteInfo(program), PreparedNiagaraVectorFields.read(program, fields),
                    new PreparedNiagaraMeshInfo(program), schedule, targets, traceShaders, fieldShaders, groomShaders);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot prepare Niagara GPU shaders " + indexPath, error);
        }
    }

    private static void require(JsonObject value, String field, String expected, Identifier path) throws IOException {
        ShaderResourceFiles.require(expected, value.get(field).getAsString(), path.toString());
    }
}
