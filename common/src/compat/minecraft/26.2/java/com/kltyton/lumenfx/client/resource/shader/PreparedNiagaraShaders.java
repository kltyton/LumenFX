package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.kltyton.lumenfx.effect.niagara.groom.HairStrandsDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Offline GPU programs and reflected layouts for one Niagara emitter. */
public record PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                    PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                    PreparedNiagaraVectorFields vectorFields, PreparedNiagaraMeshInfo meshes,
                                    NiagaraGpuStageSchedule schedule, @Nullable PreparedNiagaraTargetShaders targetShaders,
                                    @Nullable PreparedNiagaraAsyncTraceShaders asyncTraceShaders,
                                    @Nullable PreparedNiagaraPhysicsFieldShaders physicsFieldShaders,
                                    @Nullable PreparedNiagaraGroomCacheShaders groomCacheShaders) {
    public PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                  PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                  PreparedNiagaraVectorFields vectorFields, PreparedNiagaraMeshInfo meshes,
                                  NiagaraGpuStageSchedule schedule, @Nullable PreparedNiagaraTargetShaders targetShaders,
                                  @Nullable PreparedNiagaraAsyncTraceShaders asyncTraceShaders,
                                  @Nullable PreparedNiagaraPhysicsFieldShaders physicsFieldShaders) {
        this(engineHead, stages, curves, sprites, vectorFields, meshes, schedule, targetShaders, asyncTraceShaders,
                physicsFieldShaders, null);
    }
    public PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                  PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                  PreparedNiagaraVectorFields vectorFields, PreparedNiagaraMeshInfo meshes,
                                  NiagaraGpuStageSchedule schedule, @Nullable PreparedNiagaraTargetShaders targetShaders,
                                  @Nullable PreparedNiagaraAsyncTraceShaders asyncTraceShaders) {
        this(engineHead, stages, curves, sprites, vectorFields, meshes, schedule, targetShaders, asyncTraceShaders, null);
    }
    public PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                  PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                  PreparedNiagaraVectorFields vectorFields, PreparedNiagaraMeshInfo meshes,
                                  NiagaraGpuStageSchedule schedule, @Nullable PreparedNiagaraTargetShaders targetShaders) {
        this(engineHead, stages, curves, sprites, vectorFields, meshes, schedule, targetShaders, null);
    }
    public PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                  PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                  PreparedNiagaraVectorFields vectorFields, PreparedNiagaraMeshInfo meshes,
                                  NiagaraGpuStageSchedule schedule) {
        this(engineHead, stages, curves, sprites, vectorFields, meshes, schedule, null);
    }
    public PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                  PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                  PreparedNiagaraVectorFields vectorFields, NiagaraGpuStageSchedule schedule) {
        this(engineHead, stages, curves, sprites, vectorFields, new PreparedNiagaraMeshInfo(Map.of()), schedule);
    }
    public PreparedNiagaraShaders(String engineHead, Map<Integer, Map<String, Stage>> stages,
                                  PreparedNiagaraCurves curves, PreparedNiagaraSpriteInfo sprites,
                                  NiagaraGpuStageSchedule schedule) {
        this(engineHead, stages, curves, sprites, new PreparedNiagaraVectorFields(Map.of()), schedule);
    }
    public PreparedNiagaraShaders {
        Map<Integer, Map<String, Stage>> copy = new LinkedHashMap<>();
        stages.forEach((index, variants) -> copy.put(index, Map.copyOf(variants)));
        stages = Map.copyOf(copy);
    }

    public void requireSource(JsonObject program) {
        if (PreparedNiagaraAsyncTraceShaders.required(program) && asyncTraceShaders == null)
            throw new IllegalArgumentException("Missing Niagara asynchronous GPU trace shader bundle");
        if (PreparedNiagaraPhysicsFieldShaders.required(program) && physicsFieldShaders == null)
            throw new IllegalArgumentException("Missing Niagara Chaos field clipmap shader bundle");
        if (HairStrandsDefinition.gpuUsesHairStrands(program) && groomCacheShaders == null)
            throw new IllegalArgumentException("Missing Niagara groom cache interpolation shader bundle");
        var definitions = program.getAsJsonArray("variants");
        var sourceStages = program.getAsJsonArray("stages");
        if (definitions == null || sourceStages == null || stages.size() != definitions.size()
                || sourceStages.size() != definitions.size())
            throw new IllegalArgumentException("Niagara GPU stage count does not match baked shaders");
        for (var value : definitions) {
            JsonObject definition = value.getAsJsonObject();
            int index = definition.get("stage_index").getAsInt();
            if (index < 0 || index >= sourceStages.size() || !stages.containsKey(index))
                throw new IllegalArgumentException("Missing Niagara GPU stage " + index);
            for (Stage stage : stages.get(index).values()) {
                if (!definition.equals(stage.metadata.getAsJsonObject("niagaraStage"))
                        || !sourceStages.get(index).equals(stage.metadata.get("niagaraStageMetadata"))
                        || !program.get("shader_source_sha256").equals(stage.metadata.get("niagaraSourceSha256")))
                    throw new IllegalArgumentException("Niagara GPU stage metadata mismatch at " + index);
            }
        }
    }

    public record Stage(ShaderProgram shader, JsonObject metadata, boolean viewUniform) {
        public Stage(ShaderProgram shader, JsonObject metadata) {
            this(shader, metadata, readsViewUniform(metadata));
        }
        public Stage { metadata = metadata.deepCopy(); }
        @Override public JsonObject metadata() { return metadata.deepCopy(); }

        private static boolean readsViewUniform(JsonObject metadata) {
            var stages = metadata.getAsJsonArray("stages");
            if (stages == null || stages.isEmpty()) return false;
            var reflection = stages.get(0).getAsJsonObject().getAsJsonObject("reflection");
            if (reflection == null) return false;
            var buffers = reflection.getAsJsonArray("uniformBuffers");
            if (buffers == null) return false;
            for (var value : buffers) {
                var block = value.getAsJsonObject();
                var source = block.get("sourceName");
                if (source != null && source.getAsString().equals("View")) return true;
                for (var member : block.getAsJsonArray("members")) {
                    var name = member.getAsJsonObject().get("name").getAsString();
                    if (name.startsWith("View_") || name.startsWith("View.")) return true;
                }
            }
            return false;
        }
    }
}
