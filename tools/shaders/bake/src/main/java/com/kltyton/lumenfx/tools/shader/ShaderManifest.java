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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShaderManifest {
    record IncludeOverride(String from, String to) {
    }

    record Resource(
            String name,
            String format,
            String gpuFormat,
            String vkFormat,
            String dxcImageFormat,
            String hlslType,
            String originalHlslType,
            String kind,
            String access,
            Integer strideBytes,
            boolean uav,
            String declaredLowering
    ) {
        String niagaraBufferLowering() {
            if (declaredLowering != null && !declaredLowering.isBlank()) {
                return declaredLowering;
            }
            boolean core = Set.of("InputFloat", "InputInt", "StaticInputFloat", "FreeIDList", "IndirectDispatchArgs",
                    "RWOutputFloat", "RWOutputInt", "RWInstanceCounts", "RWIDToIndexTable").contains(name);
            boolean curve = "niagara_curve_lut".equals(kind) && name.matches("[A-Za-z_][A-Za-z0-9_]*_CurveLUT");
            boolean meshInfo = "niagara_mesh_renderer_info".equals(kind) && name.matches("[A-Za-z_][A-Za-z0-9_]*_MeshDataBuffer");
            boolean objectProperty = "niagara_object_property_data".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_PropertyData");
            boolean consoleVariable = "niagara_console_variable_data".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_ConsoleVariableData");
            boolean indirect = "niagara_indirect_buffer".equals(kind)
                    && Set.of("TaskInfos", "CulledInstanceCounts", "RWDrawIndirectArgs", "DrawIndirectArgsBufferOut").contains(name);
            boolean sort = "niagara_sort_buffer".equals(kind) && Set.of("NiagaraParticleDataFloat", "NiagaraParticleDataInt",
                    "GPUParticleCountBuffer", "OutKeys", "OutParticleIndices", "OutCulledParticleCounts").contains(name);
            boolean bitonic = "ue_bitonic_sort_buffer".equals(kind)
                    && Set.of("InKeys", "InValues", "OutKeys", "OutValues", "InOutKeys", "InOutValues").contains(name);
            boolean radix = "ue_radix_sort_buffer".equals(kind)
                    && Set.of("InKeys", "InValues", "InOffsets", "OutKeys", "OutValues", "OutOffsets").contains(name);
            boolean persistentIds = "niagara_persistent_id_buffer".equals(kind)
                    && Set.of("RWNewBuffer", "ExistingBuffer", "IDToIndexTable", "RWFreeIDList", "RWFreeIDListSizes").contains(name);
            boolean ribbon = "niagara_ribbon_buffer".equals(kind)
                    && Set.of("NiagaraParticleDataFloat", "NiagaraParticleDataInt", "EmitterParticleCountsBuffer",
                    "SortedIndices", "DestinationSortedIndices", "OutputTangentsAndDistances",
                    "OutputMultiRibbonIndices", "OutputSegments", "OutputTessellationStats",
                    "MultiRibbonIndices", "Segments", "TessellationStats", "PackedPerRibbonData",
                    "OutputCommandBuffer", "TangentsAndDistances", "CommandBuffer", "IndirectDrawOutput",
                    "VertexGenerationResults", "GeneratedIndicesBuffer", "IndirectDrawInfo", "TriangleToVertexIds").contains(name);
            boolean array = "niagara_array_buffer".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_Array(?:Read|RW)Buffer");
            boolean neighborGrid = "niagara_neighbor_grid_buffer".equals(kind)
                    && NiagaraNeighborGridLowering.isGridBuffer(name);
            boolean neighborQuery = "niagara_neighbor_query_buffer".equals(kind)
                    && NiagaraNeighborQueryLowering.isQueryBuffer(name);
            boolean neighborSort = "niagara_neighbor_query_sort_buffer".equals(kind)
                    && Set.of("CellIdBuffer", "CellCountBuffer", "InputBuffer", "OutputBuffer", "BlockSumsBuffer",
                        "ParticleIdIndexBuffer", "AcquireTagBuffer", "CellOffsetBuffer", "ParticleListBuffer", "AcquireTagListBuffer").contains(name);
            boolean spline = "niagara_spline_lut".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_Spline(?:Positions|Scales|Rotations)LUT");
            boolean debugDraw = "niagara_debug_draw_buffer".equals(kind)
                    && Set.of("RWNDIDebugDrawArgs", "RWNDIDebugDrawLineVertex").contains(name);
            boolean physicsAsset = "niagara_physics_asset_buffer".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:WorldTransform|InverseTransform|ElementExtent|PhysicsType)Buffer");
            boolean rigidMesh = "niagara_rigid_mesh_buffer".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:WorldTransform|InverseTransform|ElementExtent|PhysicsType|MeshScale|DFIndex)Buffer");
            boolean geometryCollection = "niagara_geometry_collection".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:(?:Prev)?World(?:Inverse)?TransformBuffer|BoundsBuffer)");
            boolean hairStrands = "niagara_hair_strands".equals(kind) && name.matches(
                    "[A-Za-z_][A-Za-z0-9_]*_(?:(?:Rest|Deformed)TrianglePosition|RootBarycentricCoordinates|RootToUniqueTriangleIndex|BoundingBox|ParamsScale)Buffer");
            boolean groomCache = "niagara_groom_cache_buffer".equals(kind) && Set.of("NiagaraFloatBuffer",
                    "RestTrianglePositionBuffer", "DeformedTrianglePositionBuffer", "RootBarycentricCoordinatesBuffer",
                    "RootToUniqueTriangleIndexBuffer").contains(name);
            boolean physicsField = "niagara_physics_field_buffer".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:ClipmapBuffer|NodesParams|NodesOffsets|TargetsOffsets)");
            boolean physicsFieldBuilder = "niagara_physics_field_builder".equals(kind)
                    && Set.of("FieldClipmap", "CellsOffsets", "CellsMin", "CellsMax", "NodesParams", "NodesOffsets", "TargetsOffsets").contains(name);
            boolean rasterizationAttributes = "niagara_rasterization_attributes".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_PerAttributeData");
            boolean velocityGrid = "niagara_velocity_grid_buffer".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_Grid(?:Current|Destination)Buffer");
            boolean grid3dAttributes = NiagaraGrid3DShaderFormats.ATTRIBUTE_KIND.equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_PerAttributeData");
            boolean tableFilter = "niagara_table_filter".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_FilteredRowDataOffsetBuffer");
            boolean particleRead = "niagara_particle_read_buffer".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:SpawnedIDsBuffer|IDToIndexTable|InputFloatBuffer|InputIntBuffer)");
            boolean lwc = "niagara_lwc_buffer".equals(kind) && Set.of("FloatBuffer", "CountBuffer").contains(name);
            boolean dispatch = "niagara_dispatch_buffer".equals(kind) && Set.of("DispatchInfos", "InstanceCounts", "RWDispatchIndirectArgs").contains(name);
            boolean stateless = "niagara_stateless_buffer".equals(kind)
                    && Set.of("Common_FloatOutputBuffer", "Common_IntOutputBuffer", "Common_GPUCountBuffer").contains(name);
            boolean asyncTrace = "niagara_async_trace_buffer".equals(kind) && "TraceCounts".equals(name);
            boolean meshBuffer = ("niagara_static_mesh_buffer".equals(kind) || "niagara_skeletal_mesh_buffer".equals(kind))
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*");
            boolean dataChannel = "niagara_data_channel_read".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:ParamOffsetTable|DataFloat|DataInt32|NDCSpawnDataBuffer)")
                    || "niagara_data_channel_write".equals(kind)
                    && name.matches("[A-Za-z_][A-Za-z0-9_]*_(?:ParamOffsetTable|(?:GPU|CPU)Buffer(?:Float|Int32))");
            if (NiagaraChaosBufferSource.KIND.equals(kind)) {
                for (var field : NiagaraChaosBufferSource.FIELDS) {
                    if (name.endsWith("_" + field.suffix()) && strideBytes != null && strideBytes == field.stride()
                            && ("buffer(" + field.element() + ")").equals(normalizeType(originalHlslType))
                            && ("structuredbuffer(" + field.storageElement() + ")").equals(normalizeType(hlslType)))
                        return NiagaraChaosBufferSource.PREFIX + field.element();
                }
                return null;
            }
            if (!core && !curve && !meshInfo && !objectProperty && !consoleVariable && !indirect && !sort && !bitonic && !radix && !persistentIds
                    && !ribbon && !array && !neighborGrid && !neighborQuery && !neighborSort && !spline && !debugDraw
                    && !rasterizationAttributes && !velocityGrid && !grid3dAttributes && !tableFilter && !physicsAsset && !rigidMesh
                    && !geometryCollection && !hairStrands && !groomCache && !physicsField && !physicsFieldBuilder
                    && !particleRead && !lwc && !dispatch && !stateless && !asyncTrace && !meshBuffer && !dataChannel) return null;
            String original = normalizeType(originalHlslType), lowered = normalizeType(hlslType);
            if (original == null || lowered == null) return null;
            for (String type : List.of("float", "float2", "float4", "int", "uint", "uint2", "uint4")) {
                int bytes = switch (type) {
                    case "float2", "uint2" -> 8;
                    case "float4", "uint4" -> 16;
                    default -> 4;
                };
                if (strideBytes == null || strideBytes != bytes) continue;
                if (original.equals("buffer(" + type + ")") && lowered.equals("structuredbuffer(" + type + ")"))
                    return "read:" + type;
                if (original.equals("rwbuffer(" + type + ")") && lowered.equals("rwstructuredbuffer(" + type + ")"))
                    return "write:" + type;
            }
            return null;
        }

        private static String normalizeType(final String value) {
            return value == null
                    ? null
                    : value.replace(" ", "").replace('<', '(').replace('>', ')').toLowerCase(java.util.Locale.ROOT);
        }
    }

    record Stage(String stage, String source, String entrypoint, Map<String, String> defines) {
    }

    record Sampler(String name, String filter, String address) {
    }

    record Variant(
            String id,
            Map<String, String> defines,
            List<String> selectionKeys,
            JsonObject selector
    ) {
    }

    record Pass(
            int order,
            String name,
            String source,
            String stage,
            String entrypoint,
            String condition,
            boolean active,
            boolean optional,
            List<Stage> stages,
            List<Variant> variants,
            List<Resource> resources,
            List<Sampler> samplers,
            Map<String, String> defines,
            JsonObject raw
    ) {
    }

    record FormatEntry(String pf, String vk, String gpuFormat, String dxcImageFormat) {
    }

    private final JsonObject root;
    private final String pipeline;
    private final String engineHead;
    private final String cacheRoot;
    private final Map<String, String> baseDefines;
    private final List<IncludeOverride> includeOverrides;
    private final List<String> unmodifiedIncludes;
    private final List<String> dxcExtraArguments;
    private final boolean reuseSharedCompat;
    private final List<String> glClipRanges;
    private final Map<String, FormatEntry> formats;
    private final JsonObject permutationContract;
    private final List<Pass> passes;

    private ShaderManifest(final JsonObject root, final String pipeline) {
        this.root = root;
        this.pipeline = pipeline;
        this.engineHead = requiredString(root.getAsJsonObject("engine"), "head");
        this.cacheRoot = string(root.getAsJsonObject("engine"), "cache_root");
        final JsonObject compile = object(root, "compile_contract");
        this.reuseSharedCompat = compile == null || !compile.has("reuse_shared_compat") || compile.get("reuse_shared_compat").getAsBoolean();
        this.baseDefines = parseStringMap(firstObject(compile, "defines_base", "base_defines", "defines"));
        this.includeOverrides = parseOverrides(firstArray(
                compile, "include_overrides", "overrides", "includeOverrides"
        ));
        this.unmodifiedIncludes = parseStringArray(firstArray(
                compile, "unmodified_engine_includes", "unmodified_includes", "engine_includes"
        ));
        this.dxcExtraArguments = parseStringArray(firstArray(
                compile, "dxc_extra_args", "extra_arguments", "dxc_flags"
        ));
        final List<String> clips = parseStringArray(firstArray(
                compile, "gl_clip_ranges", "gl_clip_range_variants", "glClipRanges"
        ));
        this.glClipRanges = clips.isEmpty()
                ? List.of("neg-one-to-one", "zero-to-one")
                : List.copyOf(clips);
        this.formats = parseFormats(object(root, "format_map"));
        this.permutationContract = firstObject(root, "permutation_contract", "rootpermutation_contract");
        this.passes = parsePasses(array(root, "passes"), this.permutationContract);
    }

    static ShaderManifest load(final Path path, final String pipeline) {
        return load(path, pipeline, null, null, null);
    }

    static ShaderManifest loadNiagara(final Path path, final String pipeline, final Path effect, final String emitter,
                                      final Path shaderSource) {
        return load(path, pipeline, effect, emitter, shaderSource);
    }

    private static ShaderManifest load(final Path path, final String pipeline, final Path effect, final String emitter,
                                       final Path shaderSource) {
        final String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new BakeException("Unable to read shader manifest " + path, error);
        }
        try {
            final JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new BakeException("Shader manifest is not a JSON object: " + path);
            }
            final JsonObject root = parsed.getAsJsonObject();
            if (effect != null) NiagaraShaderStages.expand(root, effect, emitter, pipeline, shaderSource);
            final ShaderManifest manifest = new ShaderManifest(root, pipeline);
            manifest.validate(path);
            return manifest;
        } catch (RuntimeException error) {
            if (error instanceof BakeException bake) {
                throw bake;
            }
            throw new BakeException("Unable to parse shader manifest " + path + ": " + error.getMessage(), error);
        }
    }

    private void validate(final Path path) {
        if (this.passes.isEmpty()) {
            throw new BakeException("Shader manifest has no passes: " + path);
        }
        for (Pass pass : this.passes) {
            for (Stage stage : pass.stages()) {
                if (stage.source() == null || stage.source().isBlank()) {
                    throw new BakeException("Pass " + pass.name() + " stage " + stage.stage() + " has no source");
                }
                if (stage.entrypoint() == null || stage.entrypoint().isBlank()) {
                    throw new BakeException("Pass " + pass.name() + " stage " + stage.stage() + " has no entrypoint");
                }
            }
        }
    }

    String pipeline() {
        return this.pipeline;
    }

    String engineHead() {
        return this.engineHead;
    }

    String cacheRoot() {
        return this.cacheRoot;
    }

    Map<String, String> baseDefines() {
        return this.baseDefines;
    }

    List<IncludeOverride> includeOverrides() {
        return this.includeOverrides;
    }

    List<String> unmodifiedIncludes() {
        return this.unmodifiedIncludes;
    }

    List<String> dxcExtraArguments() {
        return this.dxcExtraArguments;
    }

    boolean reuseSharedCompat() { return this.reuseSharedCompat; }

    List<String> glClipRanges() {
        return this.glClipRanges;
    }

    Map<String, FormatEntry> formats() {
        return this.formats;
    }

    List<Pass> passes() {
        return this.passes;
    }

    static List<Resource> resources(final Pass pass) {
        return pass.resources();
    }

    private static List<Pass> parsePasses(final JsonArray array, final JsonObject contract) {
        final List<Pass> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        int order = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject raw = element.getAsJsonObject();
            final String name = requiredString(raw, "name");
            final String source = string(raw, "source");
            final String stage = string(raw, "stage");
            final String entrypoint = string(raw, "entrypoint");
            final String condition = string(raw, "condition");
            final boolean active = raw.has("active") ? raw.get("active").getAsBoolean()
                    : (!raw.has("active_in_AA3") || raw.get("active_in_AA3").getAsBoolean());
            final boolean optional = raw.has("optional") && raw.get("optional").getAsBoolean();
            final List<Stage> stages = parseStages(raw, source, stage, entrypoint);
            final List<Variant> variants = parseVariants(raw, contract);
            final List<Resource> resources = parseResources(raw);
            final List<Sampler> samplers = parseSamplers(raw);
            result.add(new Pass(
                    raw.has("order") ? raw.get("order").getAsInt() : order++,
                    name,
                    source,
                    stage,
                    entrypoint,
                    condition,
                    active,
                    optional,
                    stages,
                    variants,
                    resources,
                    samplers,
                    parseStringMap(object(raw, "defines")),
                    raw
            ));
        }
        result.sort(java.util.Comparator.comparingInt(Pass::order));
        return mergeGraphicsPairs(result);
    }

    private static List<Pass> mergeGraphicsPairs(final List<Pass> passes) {
        final List<Pass> result = new ArrayList<>();
        final Set<String> consumed = new HashSet<>();
        for (int i = 0; i < passes.size(); i++) {
            final Pass first = passes.get(i);
            if (consumed.contains(first.name())) {
                continue;
            }
            if (first.stages().size() != 1 || !"vertex".equals(first.stages().get(0).stage())) {
                result.add(first);
                continue;
            }
            Pass pair = null;
            for (int j = i + 1; j < passes.size(); j++) {
                final Pass candidate = passes.get(j);
                if (consumed.contains(candidate.name())) {
                    continue;
                }
                if (candidate.stages().size() == 1
                        && "fragment".equals(candidate.stages().get(0).stage())
                        && first.source() != null
                        && first.source().equals(candidate.source())) {
                    pair = candidate;
                    break;
                }
            }
            if (pair == null) {
                result.add(first);
                continue;
            }
            final String name = mergedName(first.name(), pair.name());
            final List<Stage> stages = new ArrayList<>();
            stages.add(first.stages().get(0));
            stages.add(pair.stages().get(0));
            final List<Resource> resources = new ArrayList<>(first.resources());
            for (Resource resource : pair.resources()) {
                if (resources.stream().noneMatch(existing -> existing.name().equals(resource.name()))) {
                    resources.add(resource);
                }
            }
            result.add(new Pass(
                    Math.min(first.order(), pair.order()),
                    name,
                    first.source(),
                    first.stage(),
                    first.entrypoint(),
                    first.condition() == null ? pair.condition() : first.condition(),
                    first.active() && pair.active(),
                    first.optional() || pair.optional(),
                    List.copyOf(stages),
                    first.variants(),
                    List.copyOf(resources),
                    first.samplers(),
                    first.defines(),
                    first.raw()
            ));
            consumed.add(first.name());
            consumed.add(pair.name());
        }
        result.sort(java.util.Comparator.comparingInt(Pass::order));
        return List.copyOf(result);
    }

    private static String mergedName(final String first, final String second) {
        if (first.endsWith("VS") && second.endsWith("PS")
                && first.substring(0, first.length() - 2).equals(second.substring(0, second.length() - 2))) {
            return first.substring(0, first.length() - 2);
        }
        return first;
    }

    private static List<Variant> parseVariants(final JsonObject pass, final JsonObject contract) {
        final JsonArray explicit = firstArray(pass, "variants");
        if (explicit != null && hasObjectElements(explicit)) {
            return parseExplicitVariants(pass);
        }

        final JsonObject common = object(contract, "common");
        final JsonObject passContract = object(object(contract, "passes"), requiredString(pass, "name"));
        final Map<String, Integer> production = new LinkedHashMap<>();
        final Map<String, List<Integer>> dimensions = new LinkedHashMap<>();
        collectContract(common, production, dimensions);
        collectContract(passContract, production, dimensions);

        final List<Variant> result = new ArrayList<>();
        if (dimensions.isEmpty()) {
            final Map<String, String> defines = new LinkedHashMap<>();
            production.forEach((key, value) -> defines.put(key, Integer.toString(value)));
            result.add(new Variant("default", Map.copyOf(defines), List.of(), new JsonObject()));
            return List.copyOf(result);
        }

        final List<String> keys = new ArrayList<>(dimensions.keySet());
        expandVariants(result, keys, 0, new LinkedHashMap<>(), production, dimensions);
        return List.copyOf(result);
    }

    private static void expandVariants(
            final List<Variant> result,
            final List<String> keys,
            final int index,
            final Map<String, Integer> selected,
            final Map<String, Integer> production,
            final Map<String, List<Integer>> dimensions
    ) {
        if (index == keys.size()) {
            final Map<String, String> defines = new LinkedHashMap<>();
            production.forEach((key, value) -> defines.put(key, Integer.toString(value)));
            selected.forEach((key, value) -> defines.put(key, Integer.toString(value)));
            final List<String> selectionKeys = new ArrayList<>();
            final StringBuilder id = new StringBuilder();
            for (String key : keys) {
                final int value = selected.get(key);
                if (id.length() > 0) {
                    id.append('-');
                }
                id.append(shortKey(key)).append(value);
                selectionKeys.add(selectionKey(key));
            }
            result.add(new Variant(
                    id.toString(),
                    Map.copyOf(defines),
                    List.copyOf(selectionKeys),
                    new JsonObject()
            ));
            return;
        }
        final String key = keys.get(index);
        for (int value : dimensions.get(key)) {
            selected.put(key, value);
            expandVariants(result, keys, index + 1, selected, production, dimensions);
        }
    }

    private static void collectContract(
            final JsonObject contract,
            final Map<String, Integer> production,
            final Map<String, List<Integer>> dimensions
    ) {
        if (contract == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : contract.entrySet()) {
            final String key = entry.getKey();
            if ("remap".equals(key) || !entry.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject specification = entry.getValue().getAsJsonObject();
            final JsonArray activeValues = firstArray(specification, "active_values", "activeValues");
            if (activeValues != null && activeValues.size() > 1) {
                final List<Integer> values = new ArrayList<>();
                for (JsonElement value : activeValues) {
                    values.add(value.getAsInt());
                }
                dimensions.put(key, List.copyOf(values));
            } else {
                final Integer value = ShaderManifest.integer(specification, "production");
                if (value != null) {
                    production.put(key, value);
                } else {
                    final JsonArray values = specification.getAsJsonArray("values");
                    if (values != null && !values.isEmpty()) {
                        production.put(key, values.get(0).getAsInt());
                    }
                }
            }
        }
    }

    private static String shortKey(final String key) {
        return switch (key) {
            case "DIM_BLUR_DIRECTIONS" -> "dir";
            case "DIM_TILE_CLASSIFICATION" -> "class";
            case "DIM_ALPHA_CHANNEL" -> "alpha";
            default -> key.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String selectionKey(final String key) {
        return switch (key) {
            case "DIM_BLUR_DIRECTIONS" -> "blurDirections";
            case "DIM_TILE_CLASSIFICATION" -> "tileClassification";
            case "DIM_ALPHA_CHANNEL" -> "alphaChannel";
            default -> key;
        };
    }

    private static boolean hasObjectElements(final JsonArray array) {
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                return true;
            }
        }
        return false;
    }

    private static List<Variant> parseExplicitVariants(final JsonObject pass) {
        final JsonArray array = firstArray(pass, "variants");
        final List<Variant> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject raw = element.getAsJsonObject();
            final String id = string(raw, "id");
            final JsonArray keys = firstArray(raw, "selectionKeys", "selection_keys", "keys");
            final List<String> selectionKeys = new ArrayList<>();
            if (keys != null) {
                for (JsonElement key : keys) {
                    if (key.isJsonPrimitive()) {
                        selectionKeys.add(key.getAsString());
                    }
                }
            }
            result.add(new Variant(
                    id == null || id.isBlank() ? "default" : id,
                    parseStringMap(object(raw, "defines")),
                    List.copyOf(selectionKeys),
                    object(raw, "selector")
            ));
        }
        if (result.isEmpty()) {
            result.add(new Variant("default", Map.of(), List.of(), new JsonObject()));
        }
        return List.copyOf(result);
    }
    private static List<Stage> parseStages(
            final JsonObject pass,
            final String fallbackSource,
            final String fallbackStage,
            final String fallbackEntrypoint
    ) {
        final JsonArray array = firstArray(pass, "stages", "shaders");
        final List<Stage> result = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject raw = element.getAsJsonObject();
                result.add(new Stage(
                        normalizeStage(string(raw, "stage")),
                        firstNonBlank(string(raw, "source"), fallbackSource),
                        firstNonBlank(string(raw, "entrypoint"), fallbackEntrypoint),
                        parseStringMap(object(raw, "defines"))
                ));
            }
        }
        if (result.isEmpty()) {
            result.add(new Stage(
                    normalizeStage(fallbackStage),
                    fallbackSource,
                    fallbackEntrypoint,
                    Map.of()
            ));
        }
        return List.copyOf(result);
    }

    private static List<Variant> parseVariants(final JsonObject pass) {
        final JsonArray array = firstArray(pass, "variants", "permutations");
        final List<Variant> result = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject raw = element.getAsJsonObject();
                final String id = string(raw, "id");
                final JsonArray keys = firstArray(raw, "selectionKeys", "selection_keys", "keys");
                final List<String> selectionKeys = new ArrayList<>();
                if (keys != null) {
                    for (JsonElement key : keys) {
                        if (key.isJsonPrimitive()) {
                            selectionKeys.add(key.getAsString());
                        }
                    }
                }
                result.add(new Variant(
                        id == null || id.isBlank() ? "default" : id,
                        parseStringMap(object(raw, "defines")),
                        List.copyOf(selectionKeys),
                        object(raw, "selector")
                ));
            }
        }
        if (result.isEmpty()) {
            result.add(new Variant(
                    "default",
                    parseStringMap(object(pass, "defines")),
                    List.of(),
                    new JsonObject()
            ));
        }
        return List.copyOf(result);
    }

    private static List<Sampler> parseSamplers(final JsonObject pass) {
        final JsonArray array = firstArray(pass, "samplers");
        final List<Sampler> result = new ArrayList<>();
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject raw = element.getAsJsonObject();
                final String name = string(raw, "name");
                if (name != null) {
                    result.add(new Sampler(name, string(raw, "filter"), string(raw, "address")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Resource> parseResources(final JsonObject pass) {
        final Map<String, Resource> result = new LinkedHashMap<>();
        for (String field : List.of("srvs", "uavs", "buffers", "cbuffers", "resources")) {
            final JsonArray array = firstArray(pass, field, field + "_");
            if (array == null) {
                continue;
            }
            final boolean arrayUav = "uavs".equals(field);
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject raw = element.getAsJsonObject();
                final String name = string(raw, "name");
                if (name == null) {
                    continue;
                }
                result.put(name, new Resource(
                        name,
                        string(raw, "format"),
                        string(raw, "gpuFormat"),
                        string(raw, "vkFormat"),
                        string(raw, "dxcImageFormat"),
                        string(raw, "hlsl_type"),
                        string(raw, "original_hlsl_type"),
                        string(raw, "kind"),
                        string(raw, "access"),
                        ShaderManifest.integer(raw, "stride_bytes"),
                        arrayUav || (raw.has("uav") && raw.get("uav").getAsBoolean()),
                        string(raw, "niagara_buffer_lowering")
                ));
            }
        }
        return List.copyOf(result.values());
    }

    private static Map<String, FormatEntry> parseFormats(final JsonObject map) {
        final Map<String, FormatEntry> result = new LinkedHashMap<>();
        if (map == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : map.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            final JsonObject value = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new FormatEntry(
                    entry.getKey(),
                    string(value, "vk"),
                    string(value, "gpuFormat"),
                    string(value, "dxcImageFormat")
            ));
        }
        return result;
    }

    private static List<IncludeOverride> parseOverrides(final JsonArray array) {
        final List<IncludeOverride> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            final JsonObject raw = element.getAsJsonObject();
            result.add(new IncludeOverride(requiredString(raw, "from"), requiredString(raw, "to")));
        }
        return result;
    }

    private static List<String> parseStringArray(final JsonArray array) {
        final List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            }
        }
        return result;
    }

    private static Map<String, String> parseStringMap(final JsonObject object) {
        final Map<String, String> result = new LinkedHashMap<>();
        if (object == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            } else if (entry.getValue().isJsonObject()) {
                final JsonObject value = entry.getValue().getAsJsonObject();
                if (value.has("production") && value.get("production").isJsonPrimitive()) {
                    result.put(entry.getKey(), value.get("production").getAsString());
                }
            }
        }
        return result;
    }

    private static String normalizeStage(final String value) {
        return ShaderStage.parse(value).id();
    }

    private static String firstNonBlank(final String first, final String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static JsonObject object(final JsonObject parent, final String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(name);
    }

    private static JsonObject firstObject(final JsonObject parent, final String... names) {
        for (String name : names) {
            final JsonObject value = object(parent, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static JsonArray array(final JsonObject parent, final String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(name);
    }

    private static JsonArray firstArray(final JsonObject parent, final String... names) {
        for (String name : names) {
            final JsonArray value = array(parent, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String requiredString(final JsonObject parent, final String name) {
        final String value = string(parent, name);
        if (value == null || value.isBlank()) {
            throw new BakeException("Shader manifest is missing required field " + name);
        }
        return value;
    }

    static String string(final JsonObject object, final String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    static Integer integer(final JsonObject object, final String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsInt();
    }
}
