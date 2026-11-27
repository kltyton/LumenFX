package com.kltyton.lumenfx.runtime.model;

import com.kltyton.lumenfx.effect.noise.VectorFieldFile;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Exact 26.2-compatible Niagara system model: parses compiled CPU VectorVM scripts
 * ({@code system_spawn}, {@code system_update}, {@code emitter.spawn}, {@code emitter.update}),
 * system variable layouts & runtime input bindings, baked SPIR-V GPU compute shaders
 * ({@code shaders/compute/niagara_<hash>/index.json} -> {@code program.json} -> {@code opengl.compute.spv}),
 * and {@code .lfsc} simulation cache metadata.
 */
public final class NiagaraSystemModel {
    public enum SimTarget { CPU, GPU, STATELESS, DISABLED }
    public enum RendererKind { SPRITE, RIBBON, MESH, LIGHT, DECAL, COMPONENT, VOLUME, SPLAT }

    public record VariableSpec(String name, String sourceType,
                               int floatStart, int floatCount,
                               int intStart, int intCount,
                               int halfStart, int halfCount) { }

    public record BindingSpec(int emitter, int runtimeOffset, int type, int component) { }

    public record AttributeSpec(String name, int type, int component, int count, @Nullable String sourceType) { }

    public record ShaderBindingSpec(String name, String kind, int glBinding, String access,
                                    @Nullable Integer texelUnit, @Nullable String texelFormat) { }

    public record UniformFieldSpec(String name, int offset, int scalarBytes, int rows, int columns,
                                   int count, int arrayStride, int matrixStride, boolean rowMajor) { }

    public record UniformBlockSpec(String name, int size, List<UniformFieldSpec> fields) { }

    public record ComputeStageSpec(int stageIndex, String stageName, String variantId,
                                   int workgroupX, int workgroupY, int workgroupZ,
                                   String openglEntryPoint, byte[] openglSpirv,
                                   List<ShaderBindingSpec> bindings,
                                   Map<String, UniformBlockSpec> uniformBlocks,
                                   JsonObject metadata) { }

    public record GpuProgramSpec(String pipeline, String namespace,
                                 List<ComputeStageSpec> stages,
                                 JsonObject metadata,
                                 int floatComponentCount, int intComponentCount, int halfComponentCount) {
        public boolean hasSpirvShaders() { return !stages.isEmpty(); }
    }

    public record RendererSpec(RendererKind kind, String emitterName, String materialName,
                               @Nullable String meshAssetId, @Nullable StaticMeshModel meshModel,
                               Map<String, String> attributeBindings) { }

    public record EmitterSpec(String name, String sourceName, SimTarget simTarget,
                              boolean localSpace, int randomSeed, boolean deterministic,
                              int declaredMaxParticles,
                              int floatComponents, int intComponents, int halfComponents,
                              List<AttributeSpec> attributes,
                              List<RendererSpec> renderers,
                              List<String> spawnInfoVariables,
                              @Nullable DirectVectorProgram spawnProgram,
                              @Nullable DirectVectorProgram updateProgram,
                              @Nullable GpuProgramSpec gpuProgram,
                              @Nullable String simCacheId,
                              float explicitSpawnRate,
                              float defaultLifetime, MaterialDataCurves materialData,
                              UnityEmitterModel.RibbonConfigSpec ribbonConfig,
                              int sortOrder, boolean scaleSizeWithOwner,
                              int subImageCols, int subImageRows) {
        public EmitterSpec(String name, String sourceName, SimTarget simTarget,
                           boolean localSpace, int randomSeed, boolean deterministic,
                           int declaredMaxParticles,
                           int floatComponents, int intComponents, int halfComponents,
                           List<AttributeSpec> attributes,
                           List<RendererSpec> renderers,
                           List<String> spawnInfoVariables,
                           @Nullable DirectVectorProgram spawnProgram,
                           @Nullable DirectVectorProgram updateProgram,
                           @Nullable GpuProgramSpec gpuProgram,
                           @Nullable String simCacheId,
                           float explicitSpawnRate,
                           float defaultLifetime, MaterialDataCurves materialData) {
            this(name, sourceName, simTarget, localSpace, randomSeed, deterministic,
                    declaredMaxParticles, floatComponents, intComponents, halfComponents,
                    attributes, renderers, spawnInfoVariables, spawnProgram, updateProgram, gpuProgram,
                    simCacheId, explicitSpawnRate, defaultLifetime, materialData,
                    UnityEmitterModel.RibbonConfigSpec.DEFAULT, 0, false, 1, 1);
        }

        public boolean isGpu() { return simTarget == SimTarget.GPU && gpuProgram != null; }
        public boolean hasCpuVm() { return simTarget == SimTarget.CPU && spawnProgram != null && updateProgram != null; }
    }

    private final @Nullable DirectVectorProgram systemSpawn;
    private final @Nullable DirectVectorProgram systemUpdate;
    private final List<VariableSpec> variables;
    private final List<VariableSpec> spawnInputLayout;
    private final List<VariableSpec> updateInputLayout;
    private final List<BindingSpec> spawnInputBindings;
    private final List<BindingSpec> updateInputBindings;
    private final List<EmitterSpec> emitters;
    private final JsonObject rawNiagaraJson;

    public NiagaraSystemModel(@Nullable DirectVectorProgram systemSpawn,
                              @Nullable DirectVectorProgram systemUpdate,
                              List<VariableSpec> variables,
                              List<VariableSpec> spawnInputLayout,
                              List<VariableSpec> updateInputLayout,
                              List<BindingSpec> spawnInputBindings,
                              List<BindingSpec> updateInputBindings,
                              List<EmitterSpec> emitters,
                              JsonObject rawNiagaraJson) {
        this.systemSpawn = systemSpawn;
        this.systemUpdate = systemUpdate;
        this.variables = List.copyOf(variables);
        this.spawnInputLayout = List.copyOf(spawnInputLayout);
        this.updateInputLayout = List.copyOf(updateInputLayout);
        this.spawnInputBindings = List.copyOf(spawnInputBindings);
        this.updateInputBindings = List.copyOf(updateInputBindings);
        this.emitters = List.copyOf(emitters);
        this.rawNiagaraJson = rawNiagaraJson;
    }

    public @Nullable DirectVectorProgram systemSpawn() { return systemSpawn; }
    public @Nullable DirectVectorProgram systemUpdate() { return systemUpdate; }
    public boolean hasSystemVm() { return systemSpawn != null && systemUpdate != null; }
    public List<VariableSpec> variables() { return variables; }
    public List<VariableSpec> spawnInputLayout() { return spawnInputLayout; }
    public List<VariableSpec> updateInputLayout() { return updateInputLayout; }
    public List<BindingSpec> spawnInputBindings() { return spawnInputBindings; }
    public List<BindingSpec> updateInputBindings() { return updateInputBindings; }
    public List<EmitterSpec> emitters() { return emitters; }
    public JsonObject rawNiagaraJson() { return rawNiagaraJson; }

    public boolean hasGpuEmitters() {
        for (EmitterSpec e : emitters) if (e.isGpu()) return true;
        return false;
    }

    public static NiagaraSystemModel fromJson(String effectId, JsonObject niagaraRoot,
                                              List<UnityEmitterModel> topLevelEmitters,
                                              @Nullable McResourceBridge bridge) {
        String effectNamespace = effectId.contains(":") ? effectId.substring(0, effectId.indexOf(':')) : "lumenfx";
        Map<String, UnityEmitterModel> topByName = new LinkedHashMap<>();
        for (UnityEmitterModel e : topLevelEmitters) topByName.put(e.name(), e);
        Map<String, VectorFieldVolume> loadedFields = new LinkedHashMap<>();
        Function<String, VectorFieldVolume> fields = id -> loadedFields.computeIfAbsent(id,
                key -> readVectorField(effectNamespace, key, bridge));

        DirectVectorProgram sysSpawn = niagaraRoot.has("system_spawn") && niagaraRoot.get("system_spawn").isJsonObject()
                ? DirectVectorProgram.fromJson(niagaraRoot.getAsJsonObject("system_spawn"), fields) : null;
        DirectVectorProgram sysUpdate = niagaraRoot.has("system_update") && niagaraRoot.get("system_update").isJsonObject()
                ? DirectVectorProgram.fromJson(niagaraRoot.getAsJsonObject("system_update"), fields) : null;
        JsonArray sysLayoutArr = niagaraRoot.has("system_layout") && niagaraRoot.get("system_layout").isJsonArray()
                ? niagaraRoot.getAsJsonArray("system_layout") : niagaraRoot.getAsJsonArray("variables");
        List<VariableSpec> vars = parseVariables(sysLayoutArr);
        List<VariableSpec> spawnInLayout = parseVariables(niagaraRoot.getAsJsonArray("spawn_input_layout"));
        List<VariableSpec> updateInLayout = parseVariables(niagaraRoot.getAsJsonArray("update_input_layout"));
        List<BindingSpec> spawnBindings = parseInputBindings(niagaraRoot.getAsJsonArray("spawn_input_bindings"));
        List<BindingSpec> updateBindings = parseInputBindings(niagaraRoot.getAsJsonArray("update_input_bindings"));

        List<EmitterSpec> out = new ArrayList<>();
        JsonArray arr = niagaraRoot.has("emitters") && niagaraRoot.get("emitters").isJsonArray()
                ? niagaraRoot.getAsJsonArray("emitters") : null;
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                if (!arr.get(i).isJsonObject()) continue;
                JsonObject em = arr.get(i).getAsJsonObject();
                out.add(parseEmitter(effectNamespace, em, topByName, bridge, fields, "emitter_" + i));
            }
        }
        return new NiagaraSystemModel(sysSpawn, sysUpdate, vars, spawnInLayout, updateInLayout,
                spawnBindings, updateBindings, out, niagaraRoot);
    }

    private static VectorFieldVolume readVectorField(String defaultNamespace, String id, @Nullable McResourceBridge bridge) {
        int separator = id.indexOf(':');
        String namespace = separator < 0 ? defaultNamespace : id.substring(0, separator);
        String path = separator < 0 ? id : id.substring(separator + 1);
        String resource = namespace + ":lumenfx/vector_fields/" + path + ".lfvf";
        byte[] bytes = bridge == null ? null : bridge.readResource(resource);
        if (bytes == null) throw new IllegalArgumentException("Missing Niagara vector field " + resource);
        try {
            return VectorFieldFile.read(bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not load Niagara vector field " + resource, failure);
        }
    }

    private static List<VariableSpec> parseVariables(@Nullable JsonArray arr) {
        if (arr == null) return List.of();
        List<VariableSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject v = el.getAsJsonObject();
            out.add(new VariableSpec(
                    readString(v, "name", ""),
                    readString(v, "source_type", ""),
                    readInt(v, "float", 0),
                    readInt(v, "float_count", 0),
                    readInt(v, "int", 0),
                    readInt(v, "int_count", 0),
                    readInt(v, "half", 0),
                    readInt(v, "half_count", 0)));
        }
        return List.copyOf(out);
    }

    private static List<BindingSpec> parseInputBindings(@Nullable JsonArray arr) {
        if (arr == null) return List.of();
        List<BindingSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject b = el.getAsJsonObject();
            out.add(new BindingSpec(
                    readInt(b, "emitter", -1),
                    readInt(b, "runtime_offset", 0),
                    readInt(b, "type", 0),
                    readInt(b, "component", 0)));
        }
        return List.copyOf(out);
    }

    private static EmitterSpec parseEmitter(String effectNamespace, JsonObject em,
                                            Map<String, UnityEmitterModel> topByName,
                                            @Nullable McResourceBridge bridge,
                                            Function<String, VectorFieldVolume> fields, String fallbackName) {
        String name = readString(em, "name", fallbackName);
        String sourceName = readString(em, "source_name", name);
        UnityEmitterModel top = topByName.get(name);
        boolean localSpace = (em.has("local_space") && em.get("local_space").getAsBoolean())
                || (top != null && top.localSpace());
        int randomSeed = readInt(em, "random_seed", 0);
        boolean deterministic = em.has("deterministic") && em.get("deterministic").getAsBoolean();

        int declaredMax = readInt(em, "max_particles", top != null ? top.maxParticles() : 100000);
        if (em.has("gpu_program") && em.get("gpu_program").isJsonObject()) {
            JsonObject gp = em.getAsJsonObject("gpu_program");
            if (gp.has("max_particles")) declaredMax = Math.max(declaredMax, readInt(gp, "max_particles", declaredMax));
        }

        List<AttributeSpec> attributes = new ArrayList<>();
        int floatComps = 0, intComps = 0, halfComps = 0;
        if (em.has("attributes") && em.get("attributes").isJsonArray()) {
            for (JsonElement aEl : em.getAsJsonArray("attributes")) {
                if (!aEl.isJsonObject()) continue;
                JsonObject a = aEl.getAsJsonObject();
                int type = readInt(a, "type", 0);
                int comp = readInt(a, "component", 0);
                int count = readInt(a, "count", 1);
                attributes.add(new AttributeSpec(readString(a, "name", ""), type, comp, count,
                        a.has("source_type") && a.get("source_type").isJsonPrimitive() ? a.get("source_type").getAsString() : null));
                if (type == 0) floatComps = Math.max(floatComps, comp + count);
                else if (type == 1) intComps = Math.max(intComps, comp + count);
                else if (type == 2) halfComps = Math.max(halfComps, comp + count);
            }
        }

        List<RendererSpec> renderers = new ArrayList<>();
        if (em.has("renderers") && em.get("renderers").isJsonArray()) {
            for (JsonElement rEl : em.getAsJsonArray("renderers")) {
                if (!rEl.isJsonObject()) continue;
                renderers.add(parseRenderer(rEl.getAsJsonObject(), name, top, bridge));
            }
        }
        if (renderers.isEmpty()) {
            RendererKind rk = top != null ? mapRendererKind(top.renderer()) : parseRendererKind(readString(em, "renderer", "sprite"));
            String mat = top != null ? top.materialName() : readString(em, "material", "");
            String meshId = top != null && top.meshEntry() != null ? top.meshEntry().assetId() : null;
            StaticMeshModel meshModel = top != null && top.meshEntry() != null ? top.meshEntry().mesh() : null;
            renderers.add(new RendererSpec(rk, name, mat, meshId, meshModel, Map.of()));
        }

        List<String> spawnInfoVars = new ArrayList<>();
        if (em.has("spawn_info") && em.get("spawn_info").isJsonArray()) {
            for (JsonElement sEl : em.getAsJsonArray("spawn_info")) {
                if (sEl.isJsonPrimitive()) spawnInfoVars.add(sEl.getAsString());
            }
        }

        SimTarget target = SimTarget.CPU;
        GpuProgramSpec gpuSpec = null;
        DirectVectorProgram spawnProg = null;
        DirectVectorProgram updateProg = null;
        if (em.has("disabled") && em.get("disabled").isJsonObject()) {
            target = SimTarget.DISABLED;
        } else if (em.has("stateless") && em.get("stateless").isJsonObject()) {
            target = SimTarget.STATELESS;
        } else if (em.has("gpu_program") && em.get("gpu_program").isJsonObject()) {
            target = SimTarget.GPU;
            JsonObject gpuObj = em.getAsJsonObject("gpu_program");
            String pipeline = readString(gpuObj, "shader_pipeline", "");
            if (pipeline.isBlank()) pipeline = readString(gpuObj, "pipeline", "");
            if (pipeline.isBlank() && gpuObj.has("shader") && gpuObj.get("shader").isJsonPrimitive()) {
                pipeline = gpuObj.get("shader").getAsString();
            }
            String ns = effectNamespace;
            if (gpuObj.has("hlsl_source") && gpuObj.get("hlsl_source").isJsonPrimitive()) {
                String hlsl = gpuObj.get("hlsl_source").getAsString();
                if (hlsl.contains(":")) ns = hlsl.substring(0, hlsl.indexOf(':'));
            }
            if (pipeline.contains(":")) {
                int c = pipeline.indexOf(':');
                ns = pipeline.substring(0, c);
                pipeline = pipeline.substring(c + 1);
            }
            if (gpuObj.has("float_components")) floatComps = Math.max(floatComps, readInt(gpuObj, "float_components", floatComps));
            if (gpuObj.has("int_components")) intComps = Math.max(intComps, readInt(gpuObj, "int_components", intComps));
            if (gpuObj.has("half_components")) halfComps = Math.max(halfComps, readInt(gpuObj, "half_components", halfComps));
            List<ComputeStageSpec> stages = loadBakedComputeStages(ns, pipeline, bridge);
            gpuSpec = new GpuProgramSpec(pipeline, ns, stages, gpuObj,
                    Math.max(1, floatComps), Math.max(1, intComps), Math.max(0, halfComps));
        } else {
            if (em.has("spawn") && em.get("spawn").isJsonObject()) {
                spawnProg = DirectVectorProgram.fromJson(em.getAsJsonObject("spawn"), fields);
            }
            if (em.has("update") && em.get("update").isJsonObject()) {
                updateProg = DirectVectorProgram.fromJson(em.getAsJsonObject("update"), fields);
            }
        }

        String simCacheId = null;
        if (em.has("sim_cache") && em.get("sim_cache").isJsonPrimitive()) {
            simCacheId = em.get("sim_cache").getAsString();
        }
        // Only honor an explicit spawn_rate field (synthetic non-VM definitions); NEVER inject a fake 40/s rate!
        float explicitSpawnRate = em.has("spawn_rate") && em.get("spawn_rate").isJsonPrimitive()
                ? (float) readDouble(em, "spawn_rate", 0.0) : 0.0f;
        float lifetime = (float) readDouble(em, "lifetime", 1.5);
        UnityEmitterModel.RibbonConfigSpec ribbonCfg = top != null ? top.ribbonConfig() : UnityEmitterModel.RibbonConfigSpec.DEFAULT;
        int sortOrder = top != null ? top.sortOrder() : readInt(em, "sort_order", 0);
        boolean scaleSizeWithOwner = top != null && top.scaleSizeWithOwner();
        int subCols = top != null ? top.atlasColumns() : 1;
        int subRows = top != null ? top.atlasRows() : 1;
        if (em.has("renderers") && em.get("renderers").isJsonArray()) {
            for (JsonElement rEl : em.getAsJsonArray("renderers")) {
                if (!rEl.isJsonObject()) continue;
                JsonObject rObj = rEl.getAsJsonObject();
                if (rObj.has("sub_image_size") && rObj.get("sub_image_size").isJsonArray()) {
                    JsonArray sz = rObj.getAsJsonArray("sub_image_size");
                    if (sz.size() >= 2) {
                        subCols = Math.max(1, (int) Math.round(sz.get(0).getAsDouble()));
                        subRows = Math.max(1, (int) Math.round(sz.get(1).getAsDouble()));
                    }
                }
                break;
            }
        }
        return new EmitterSpec(name, sourceName, target, localSpace, randomSeed, deterministic,
                declaredMax, Math.max(1, floatComps), Math.max(1, intComps), Math.max(0, halfComps),
                attributes, renderers, spawnInfoVars, spawnProg, updateProg, gpuSpec, simCacheId,
                explicitSpawnRate, lifetime, top != null ? top.materialData() : MaterialDataCurves.EMPTY,
                ribbonCfg, sortOrder, scaleSizeWithOwner, subCols, subRows);
    }

    private static RendererSpec parseRenderer(JsonObject r, String defaultEmitter,
                                              @Nullable UnityEmitterModel top, @Nullable McResourceBridge bridge) {
        String emName = readString(r, "emitter", defaultEmitter);
        RendererKind kind = parseRendererKind(readString(r, "type",
                readString(r, "renderer", top != null ? top.renderer().name() : "sprite")));
        String mat = readString(r, "material", top != null ? top.materialName() : "");
        String meshId = null;
        StaticMeshModel meshModel = null;
        if (r.has("mesh") && r.get("mesh").isJsonObject()) {
            JsonObject mObj = r.getAsJsonObject("mesh");
            if (mObj.has("entries") && mObj.get("entries").isJsonArray() && mObj.getAsJsonArray("entries").size() > 0) {
                JsonObject e0 = mObj.getAsJsonArray("entries").get(0).getAsJsonObject();
                meshId = readString(e0, "asset", null);
                if (meshId != null) meshModel = StaticMeshModel.loadLumenMesh(meshId, bridge);
            }
        } else if (top != null && top.meshEntry() != null) {
            meshId = top.meshEntry().assetId();
            meshModel = top.meshEntry().mesh();
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        if (r.has("attribute_bindings") && r.get("attribute_bindings").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : r.getAsJsonObject("attribute_bindings").entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("name")) {
                    bindings.put(e.getKey(), e.getValue().getAsJsonObject().get("name").getAsString());
                } else if (e.getValue().isJsonPrimitive()) {
                    bindings.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return new RendererSpec(kind, emName, mat, meshId, meshModel, bindings);
    }

    public static List<ComputeStageSpec> loadBakedComputeStages(String namespace, String pipeline,
                                                                @Nullable McResourceBridge bridge) {
        if (bridge == null || pipeline == null || pipeline.isBlank()) return List.of();
        String indexId = namespace + ":shaders/compute/" + pipeline + "/index.json";
        byte[] indexBytes = bridge.readResource(indexId);
        if (indexBytes == null) return List.of();
        List<ComputeStageSpec> stages = new ArrayList<>();
        try {
            JsonObject index = JsonParser.parseString(new String(indexBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray programs = index.getAsJsonArray("programs");
            if (programs == null) return List.of();
            for (JsonElement item : programs) {
                if (!item.isJsonObject()) continue;
                JsonObject entry = item.getAsJsonObject();
                String variant = readString(entry, "variant", "default");
                if (!variant.equals("default")) continue;
                String relPath = readString(entry, "path", "");
                if (relPath.isBlank() || relPath.contains("..")) continue;
                String progId = namespace + ":shaders/compute/" + relPath;
                byte[] progBytes = bridge.readResource(progId);
                if (progBytes == null) continue;
                JsonObject meta = JsonParser.parseString(new String(progBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                int stageIndex = meta.has("niagaraStage") && meta.get("niagaraStage").isJsonObject()
                        ? readInt(meta.getAsJsonObject("niagaraStage"), "stage_index", stages.size()) : stages.size();
                String stageName = meta.has("niagaraStage") && meta.get("niagaraStage").isJsonObject()
                        ? readString(meta.getAsJsonObject("niagaraStage"), "stage_name", "stage_" + stageIndex)
                        : "stage_" + stageIndex;
                JsonArray stageArr = meta.getAsJsonArray("stages");
                if (stageArr == null || stageArr.size() == 0) continue;
                JsonObject stageObj = stageArr.get(0).getAsJsonObject();
                JsonObject wg = stageObj.getAsJsonObject("workgroup");
                int wgX = wg != null ? readInt(wg, "x", 64) : 64;
                int wgY = wg != null ? readInt(wg, "y", 1) : 1;
                int wgZ = wg != null ? readInt(wg, "z", 1) : 1;
                String glEntry = readString(stageObj, "openglEntryPoint", "main");

                JsonObject artifacts = stageObj.getAsJsonObject("artifacts");
                byte[] spirv = null;
                if (artifacts != null && artifacts.has("opengl") && artifacts.get("opengl").isJsonObject()) {
                    String spvRel = readString(artifacts.getAsJsonObject("opengl"), "path", "opengl.compute.spv");
                    int slash = progId.lastIndexOf('/');
                    String spvId = (slash >= 0 ? progId.substring(0, slash + 1) : (namespace + ":shaders/compute/")) + spvRel;
                    spirv = bridge.readResource(spvId);
                }
                if (spirv == null || spirv.length < 20) continue;

                List<ShaderBindingSpec> bindings = parseBindings(stageObj.getAsJsonArray("bindings"));
                Map<String, UniformBlockSpec> uboLayouts = parseUniformBlocks(stageObj);
                stages.add(new ComputeStageSpec(stageIndex, stageName, variant, wgX, wgY, wgZ,
                        glEntry, spirv, bindings, uboLayouts, meta));
            }
        } catch (RuntimeException ignored) { }
        return List.copyOf(stages);
    }

    private static List<ShaderBindingSpec> parseBindings(@Nullable JsonArray arr) {
        if (arr == null) return List.of();
        List<ShaderBindingSpec> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject b = el.getAsJsonObject();
            Integer texelUnit = null;
            String texelFormat = null;
            if (b.has("openGlTexelView") && b.get("openGlTexelView").isJsonObject()) {
                JsonObject tv = b.getAsJsonObject("openGlTexelView");
                texelUnit = readInt(tv, "unit", 0);
                texelFormat = readString(tv, "format", "R32F");
            }
            out.add(new ShaderBindingSpec(
                    readString(b, "name", ""),
                    readString(b, "kind", ""),
                    readInt(b, "glBinding", 0),
                    readString(b, "access", "READ_ONLY"),
                    texelUnit, texelFormat));
        }
        return List.copyOf(out);
    }

    private static Map<String, UniformBlockSpec> parseUniformBlocks(JsonObject stageObj) {
        Map<String, UniformBlockSpec> out = new LinkedHashMap<>();
        if (!stageObj.has("reflection") || !stageObj.get("reflection").isJsonObject()) return out;
        JsonObject refl = stageObj.getAsJsonObject("reflection");
        if (!refl.has("uniformBuffers") || !refl.get("uniformBuffers").isJsonArray()) return out;
        for (JsonElement el : refl.getAsJsonArray("uniformBuffers")) {
            if (!el.isJsonObject()) continue;
            JsonObject block = el.getAsJsonObject();
            String name = readString(block, "name", "");
            int size = readInt(block, "size", 0);
            if (name.isBlank() || size <= 0) continue;
            List<UniformFieldSpec> fields = new ArrayList<>();
            readUniformMembers(block.getAsJsonArray("members"), "", 0, fields);
            out.put(name, new UniformBlockSpec(name, size, List.copyOf(fields)));
        }
        return out;
    }

    private static void readUniformMembers(@Nullable JsonArray members, String prefix, int base, List<UniformFieldSpec> out) {
        if (members == null) return;
        for (JsonElement item : members) {
            if (!item.isJsonObject()) continue;
            JsonObject m = item.getAsJsonObject();
            String name = prefix + readString(m, "name", "");
            int offset = base + readInt(m, "offset", 0);
            JsonArray dims = m.getAsJsonArray("arrayDimensions");
            int count = (dims != null && dims.size() > 0) ? dims.get(0).getAsInt() : 1;
            JsonArray children = m.getAsJsonArray("members");
            if (children != null && children.size() > 0) {
                int stride = readInt(m, "arrayStride", 0);
                for (int i = 0; i < count; i++) {
                    readUniformMembers(children, name + (dims != null && dims.size() > 0 ? "[" + i + "]" : "") + ".",
                            offset + i * stride, out);
                }
                continue;
            }
            int bits = readInt(m, "bitWidth", 32);
            int rows = readInt(m, "vectorSize", 1);
            int cols = readInt(m, "columns", 1);
            int scalarBytes = Math.max(1, bits / 8);
            int matStride = readInt(m, "matrixStride", rows * scalarBytes);
            int arrStride = readInt(m, "arrayStride", cols * rows * scalarBytes);
            boolean rowMajor = m.has("rowMajor") && m.get("rowMajor").getAsBoolean();
            out.add(new UniformFieldSpec(name, offset, scalarBytes, rows, cols, count, arrStride, matStride, rowMajor));
        }
    }

    private static RendererKind mapRendererKind(UnityEmitterModel.RendererKind r) {
        return switch (r) {
            case RIBBON -> RendererKind.RIBBON;
            case MESH -> RendererKind.MESH;
            case LIGHT -> RendererKind.LIGHT;
            case DECAL -> RendererKind.DECAL;
            case VOLUME -> RendererKind.VOLUME;
            case SPLAT -> RendererKind.SPLAT;
            default -> RendererKind.SPRITE;
        };
    }

    private static RendererKind parseRendererKind(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (s.contains("RIBBON")) return RendererKind.RIBBON;
        if (s.contains("MESH")) return RendererKind.MESH;
        if (s.contains("LIGHT")) return RendererKind.LIGHT;
        if (s.contains("DECAL")) return RendererKind.DECAL;
        if (s.contains("VOLUME")) return RendererKind.VOLUME;
        if (s.contains("SPLAT")) return RendererKind.SPLAT;
        if (s.contains("COMPONENT")) return RendererKind.COMPONENT;
        return RendererKind.SPRITE;
    }

    public static boolean isValidSimCacheBytes(@Nullable byte[] raw) {
        if (raw == null || raw.length < 16) return false;
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int magic = b.getInt();
        int version = b.getInt();
        return magic == 0x4353464c && version >= 1 && version <= 6;
    }

    private static String readString(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static int readInt(JsonObject o, String key, int fallback) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double readDouble(JsonObject o, String key, double fallback) {
        try { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }
}
