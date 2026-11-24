package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Expands the saved Niagara simulation stages against an explicit shader environment. */
final class NiagaraShaderStages {
    private NiagaraShaderStages() { }

    static void expand(JsonObject manifest, Path effectPath, String emitterName, String pipeline, Path shaderSource) {
        final JsonObject effect;
        try {
            effect = JsonParser.parseString(Files.readString(effectPath, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            throw new BakeException("Cannot read Niagara effect " + effectPath, error);
        }
        JsonObject niagara = effect.getAsJsonObject("niagara");
        if (niagara == null || !niagara.has("emitters"))
            throw new BakeException("Effect has no Niagara emitters: " + effectPath);
        JsonObject selected = null;
        for (JsonElement item : niagara.getAsJsonArray("emitters")) {
            JsonObject candidate = item.getAsJsonObject();
            if (candidate.has("name") && emitterName.equals(candidate.get("name").getAsString())) {
                if (selected != null) throw new BakeException("Duplicate Niagara emitter " + emitterName);
                selected = candidate;
            }
        }
        if (selected == null || !selected.has("gpu_program"))
            throw new BakeException("No GPU program for Niagara emitter " + emitterName);
        JsonObject program = selected.getAsJsonObject("gpu_program");
        Set<String> writableArrays = writableArrays(niagara.getAsJsonArray("emitters"));
        if (!program.has("shader_pipeline") || !pipeline.equals(program.get("shader_pipeline").getAsString()))
            throw new BakeException("Shader pipeline does not match Niagara emitter " + emitterName);
        String sourceHash = program.get("shader_source_sha256").getAsString();
        final String generatedSource;
        try {
            byte[] bytes = Files.readAllBytes(shaderSource);
            if (!sourceHash.equals(ShaderOutputWriter.sha256(bytes)))
                throw new BakeException("Shader source does not match Niagara emitter " + emitterName);
            generatedSource = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new BakeException("Cannot read Niagara shader source " + shaderSource, error);
        }
        JsonArray sourceStages = program.getAsJsonArray("variants");
        JsonArray stageMetadata = program.getAsJsonArray("stages");
        JsonArray templates = manifest.getAsJsonArray("passes");
        if (sourceStages == null || sourceStages.isEmpty() || stageMetadata == null
                || sourceStages.size() != stageMetadata.size() || templates == null || templates.size() != 1)
            throw new BakeException("Niagara shader requires saved stages and exactly one pass template");
        JsonObject template = templates.get(0).getAsJsonObject();
        JsonArray expanded = new JsonArray();
        Set<Integer> indices = new HashSet<>();
        for (JsonElement item : sourceStages) {
            JsonObject source = item.getAsJsonObject();
            int index = source.get("stage_index").getAsInt();
            if (index < 0 || index >= stageMetadata.size() || !indices.add(index))
                throw new BakeException("Invalid Niagara stage index " + index);
            JsonObject stage = template.deepCopy();
            stage.addProperty("name", "stage_" + index);
            stage.addProperty("order", index);
            stage.addProperty("entrypoint", program.get("entry_point").getAsString());
            JsonObject defines = stage.has("defines") ? stage.getAsJsonObject("defines") : new JsonObject();
            source.getAsJsonObject("defines").entrySet().forEach(e -> defines.add(e.getKey(), e.getValue().deepCopy()));
            stage.add("defines", defines);
            stage.add("niagara_stage", source.deepCopy());
            stage.add("niagara_stage_metadata", stageMetadata.get(index).deepCopy());
            stage.addProperty("niagara_source_sha256", sourceHash);
            addParticleBuffers(stage);
            addCurveBuffers(stage, program);
            addMeshRendererInformation(stage, program);
            addObjectPropertyReaders(stage, program);
            addPropertyInterfaces(stage, program);
            addConsoleVariables(stage, program);
            addAsyncTraceBuffers(stage, program);
            addStaticMeshBuffers(stage, program);
            addSharedMeshFields(stage, program);
            addSceneCaptureTextures(stage, program);
            addLandscapeTextures(stage, program);
            addVirtualTextures(stage, program);
            addDebugDrawBuffers(stage, program);
            addSkeletalMeshBuffers(stage, program);
            addParticleExportBuffers(stage, program);
            addDataChannelReadBuffers(stage, program);
            addDataChannelWriteBuffers(stage, program);
            addAudioAnalysisBuffers(stage, program);
            addSimulationCacheBuffers(stage, program);
            addMemoryBuffers(stage, program);
            addDataTableBuffers(stage, program);
            addGaussianSplatBuffers(stage, program);
            addIntDistributionBuffers(stage, program);
            addIntRenderTargets(stage, program);
            addSocketBuffers(stage, program);
            addSplineBuffers(stage, program);
            addCollisionBuffers(stage, program);
            addPhysicsFieldBuffers(stage, program);
            addGeometryCollectionBuffers(stage, program);
            addChaosDestructionBuffers(stage, program);
            addHairStrandsBuffers(stage, program);
            addNeighborQueryBuffers(stage, program);
            addDynamicMeshBuffers(stage, program);
            addVectorFields(stage, program);
            addTextures(stage, program);
            addSparseVolumes(stage, program);
            addRenderTargets(stage, program);
            addRasterizationGrids(stage, program);
            addVelocityGrids(stage, program);
            NiagaraGrid3DShaderFormats.add(stage, program, generatedSource);
            addArrayBuffers(stage, program, shaderSource, writableArrays);
            addParticleReaders(stage, program, shaderSource);
            addSceneDepth(stage, program);
            addOcclusionScene(stage, program);
            addGBufferScene(stage, program);
            addGlobalDistanceField(stage, program);
            expanded.add(stage);
        }
        manifest.add("passes", expanded);
    }

    private static void addParticleBuffers(JsonObject stage) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        addBuffer(resources, "InputFloat", "float", false, 4);
        addBuffer(resources, "InputInt", "int", false, 4);
        addBuffer(resources, "StaticInputFloat", "float", false, 4);
        addBuffer(resources, "FreeIDList", "int", false, 4);
        addBuffer(resources, "IndirectDispatchArgs", "uint4", false, 16);
        addBuffer(resources, "RWOutputFloat", "float", true, 4);
        addBuffer(resources, "RWOutputInt", "int", true, 4);
        addBuffer(resources, "RWInstanceCounts", "uint", true, 4);
        addBuffer(resources, "RWIDToIndexTable", "int", true, 4);
        stage.add("resources", resources);
        stage.addProperty("niagara_packed_half", true);
    }

    private static void addBuffer(JsonArray resources, String name, String type, boolean writable, int stride) {
        JsonObject resource = new JsonObject();
        resource.addProperty("name", name);
        resource.addProperty("kind", "niagara_particle_buffer");
        resource.addProperty("original_hlsl_type", (writable ? "RWBuffer<" : "Buffer<") + type + ">");
        resource.addProperty("hlsl_type", (writable ? "RWStructuredBuffer<" : "StructuredBuffer<") + type + ">");
        resource.addProperty("stride_bytes", stride);
        resource.addProperty("uav", writable);
        resources.add(resource);
    }

    private static void addCurveBuffers(JsonObject stage, JsonObject program) {
        JsonObject metadata = program.getAsJsonObject("parameter_metadata");
        JsonArray interfaces = metadata == null ? null : metadata.getAsJsonArray("DataInterfaceParamInfo");
        if (interfaces == null) return;
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        Set<String> curves = Set.of("NiagaraDataInterfaceCurve", "NiagaraDataInterfaceColorCurve",
                "NiagaraDataInterfaceVector2DCurve", "NiagaraDataInterfaceVectorCurve", "NiagaraDataInterfaceVector4Curve");
        for (JsonElement item : interfaces) {
            JsonObject binding = item.getAsJsonObject();
            if (!curves.contains(binding.get("DIClassName").getAsString())) continue;
            String symbol = binding.get("DataInterfaceHLSLSymbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara data interface shader symbol " + symbol);
            JsonObject resource = new JsonObject();
            resource.addProperty("name", symbol + "_CurveLUT");
            resource.addProperty("kind", "niagara_curve_lut");
            resource.addProperty("original_hlsl_type", "Buffer<float>");
            resource.addProperty("hlsl_type", "StructuredBuffer<float>");
            resource.addProperty("stride_bytes", 4);
            resources.add(resource);
        }
        stage.add("resources", resources);
    }

    private static void addMeshRendererInformation(JsonObject stage, JsonObject program) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceMeshRendererInfo".equals(binding.get("class").getAsString())
                    || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara mesh information shader symbol " + symbol);
            JsonObject resource = new JsonObject();
            resource.addProperty("name", symbol + "_MeshDataBuffer");
            resource.addProperty("kind", "niagara_mesh_renderer_info");
            resource.addProperty("original_hlsl_type", "Buffer<float>");
            resource.addProperty("hlsl_type", "StructuredBuffer<float>");
            resource.addProperty("stride_bytes", 4);
            resources.add(resource);
        }
        stage.add("resources", resources);
    }

    private static void addObjectPropertyReaders(JsonObject stage, JsonObject program) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceUObjectPropertyReader".equals(binding.get("class").getAsString())
                    || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara object reader shader symbol " + symbol);
            var resource = new JsonObject();
            resource.addProperty("name", symbol + "_PropertyData");
            resource.addProperty("kind", "niagara_object_property_data");
            resource.addProperty("original_hlsl_type", "Buffer<uint>");
            resource.addProperty("hlsl_type", "StructuredBuffer<uint>");
            resource.addProperty("stride_bytes", Integer.BYTES);
            resources.add(resource);
        }
        stage.add("resources", resources);
    }

    private static void addConsoleVariables(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceConsoleVariable".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara console variable shader symbol " + symbol);
            var resource = new JsonObject();
            resource.addProperty("name", symbol + "_ConsoleVariableData");
            resource.addProperty("kind", "niagara_console_variable_data");
            resource.addProperty("original_hlsl_type", "Buffer<uint>");
            resource.addProperty("hlsl_type", "StructuredBuffer<uint>");
            resource.addProperty("stride_bytes", Integer.BYTES);
            resources.add(resource);
        }
    }

    private static void addPropertyInterfaces(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("property_interface_outputs")) continue;
            var data = new JsonObject();
            data.addProperty("name", binding.get("shader_symbol").getAsString() + "_PropertyInterfaceData");
            data.addProperty("kind", "niagara_property_interface");
            data.addProperty("hlsl_type", "ByteAddressBuffer");
            data.addProperty("stride_bytes", Integer.BYTES);
            resources.add(data);
        }
    }

    private static void addAsyncTraceBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceAsyncGpuTrace".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara asynchronous GPU trace shader symbol " + symbol);
            var counts = new JsonObject();
            counts.addProperty("name", symbol + "_RWRayTraceCounts");
            counts.addProperty("kind", "niagara_async_trace_counts");
            counts.addProperty("original_hlsl_type", "RWBuffer<uint>");
            counts.addProperty("hlsl_type", "RWStructuredBuffer<uint>");
            counts.addProperty("stride_bytes", Integer.BYTES);
            counts.addProperty("uav", true);
            resources.add(counts);
        }
    }

    private record MeshBuffer(String name, String element, int words) { }

    private static void addStaticMeshBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceStaticMesh".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara static mesh shader symbol " + symbol);
            for (var field : List.of(new MeshBuffer("IndexBuffer", "uint", 1), new MeshBuffer("PositionBuffer", "float", 1),
                    new MeshBuffer("TangentBuffer", "float4", 4), new MeshBuffer("UVBuffer", "float2", 2),
                    new MeshBuffer("ColorBuffer", "float4", 4), new MeshBuffer("UniformSamplingTriangles", "uint2", 2),
                    new MeshBuffer("SectionInfos", "uint4", 4), new MeshBuffer("FilteredAndUnfilteredSections", "uint", 1),
                    new MeshBuffer("SocketTransforms", "float4", 4), new MeshBuffer("FilteredAndUnfilteredSockets", "uint", 1),
                    new MeshBuffer("UvMappingBuffer", "int", 1))) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field.name());
                resource.addProperty("kind", "niagara_static_mesh_buffer");
                resource.addProperty("original_hlsl_type", "Buffer<" + field.element() + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + field.element() + ">");
                resource.addProperty("stride_bytes", field.words() * Integer.BYTES);
                resources.add(resource);
            }
            addSceneTexture(resources, symbol + "_MeshFieldTable", "niagara_mesh_distance_field_table", "Texture3D<uint>", "PF_R32_UINT");
            addSceneTexture(resources, symbol + "_MeshFieldAtlas", "niagara_mesh_distance_field_atlas", "Texture3D<float>", "PF_R8");
        }
    }

    private static void addSkeletalMeshBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceSkeletalMesh".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara skeletal mesh shader symbol " + symbol);
            var defines = stage.getAsJsonObject("defines");
            var sourceDefines = stage.getAsJsonObject("niagara_stage").getAsJsonObject("defines");
            skeletalDefine(defines, sourceDefines, "DISKELMESH_BONE_INFLUENCES", 2);
            skeletalDefine(defines, sourceDefines, "DISKELMESH_ALLOW_16BIT", 1);
            skeletalDefine(defines, sourceDefines, "DISKELMESH_ALLOW_DEFORMED", 1);
            skeletalDefine(defines, sourceDefines, "DISKELMESH_PROBALIAS_FORMAT", 0);
            skeletalDefine(defines, sourceDefines, "DISKELMESH_ADJ_INDEX_FORMAT", 0);
            for (var field : List.of(new MeshBuffer("MeshIndexBuffer", "uint", 1), new MeshBuffer("MeshVertexBuffer", "float", 1),
                    new MeshBuffer("MeshSkinWeightBuffer", "uint", 1), new MeshBuffer("MeshSkinWeightLookupBuffer", "uint", 1),
                    new MeshBuffer("MeshCurrBonesBuffer", "float4", 4), new MeshBuffer("MeshPrevBonesBuffer", "float4", 4),
                    new MeshBuffer("MeshCurrSamplingBonesBuffer", "float4", 4), new MeshBuffer("MeshPrevSamplingBonesBuffer", "float4", 4),
                    new MeshBuffer("MeshTangentBuffer", "float4", 4), new MeshBuffer("MeshTexCoordBuffer", "float2", 2),
                    new MeshBuffer("MeshColorBuffer", "float4", 4), new MeshBuffer("MeshTriangleSamplerProbAliasBuffer", "uint", 1),
                    new MeshBuffer("MeshSamplingRegionsProbAliasBuffer", "uint", 1), new MeshBuffer("MeshSampleRegionsTriangleIndices", "uint", 1),
                    new MeshBuffer("MeshSampleRegionsVertices", "uint", 1), new MeshBuffer("MeshTriangleMatricesOffsetBuffer", "uint", 1),
                    new MeshBuffer("FilteredAndUnfilteredBones", "uint", 1), new MeshBuffer("UvMappingBuffer", "int", 1),
                    new MeshBuffer("ConnectivityBuffer", "uint", 1), new MeshBuffer("DeformedCurrPositionBuffer", "float", 1),
                    new MeshBuffer("DeformedPrevPositionBuffer", "float", 1), new MeshBuffer("DeformedTangentBuffer", "float4", 4))) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field.name());
                resource.addProperty("kind", "niagara_skeletal_mesh_buffer");
                resource.addProperty("original_hlsl_type", "Buffer<" + field.element() + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + field.element() + ">");
                resource.addProperty("stride_bytes", field.words() * Integer.BYTES);
                resources.add(resource);
            }
        }
    }

    private static void skeletalDefine(JsonObject defines, JsonObject source, String name, int fallback) {
        defines.addProperty(name, source.has(name) ? source.get(name).getAsInt() : fallback);
    }

    private static void addSharedMeshFields(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        boolean present = false;
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("shader_symbol")) continue;
            String kind = switch (binding.get("class").getAsString()) {
                case "NiagaraDataInterfaceStaticMeshIndirect" -> NiagaraSharedMeshDistanceSource.KIND;
                case "NiagaraDataInterfaceRigidMeshCollisionQuery" -> NiagaraSharedMeshDistanceSource.RIGID_KIND;
                default -> null;
            };
            if (kind == null) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara shared mesh field shader symbol " + symbol);
            var source = new JsonObject();
            source.addProperty("name", symbol);
            source.addProperty("kind", kind);
            resources.add(source);
            present = true;
        }
        if (!present) return;
        addSceneTexture(resources, "LfxSharedMeshTable", "niagara_mesh_distance_field_table", "Texture3D<uint>", "PF_R32_UINT");
        addSceneTexture(resources, "LfxSharedMeshAtlas", "niagara_mesh_distance_field_atlas", "Texture3D<float>", "PF_R8");
    }

    private static void addSceneCaptureTextures(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("scene_capture_2d") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara Scene Capture 2D shader symbol " + symbol);
            addSceneTexture(resources, symbol + "_Texture", "niagara_texture_2d", "Texture2D", "PF_FloatRGBA");
        }
    }

    private static void addLandscapeTextures(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("landscape") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara Landscape shader symbol " + symbol);
            for (String field : List.of("CachedHeightTexture", "BaseColorVirtualTexture", "HeightVirtualTexture",
                    "NormalVirtualTexture0", "NormalVirtualTexture1"))
                addSceneTexture(resources, symbol + "_" + field, "niagara_texture_2d", "Texture2D", "PF_R32_FLOAT");
            for (String field : List.of("BaseColor", "Height", "Normal"))
                addSceneTexture(resources, symbol + "_" + field + "VirtualTexturePageTable",
                        "niagara_virtual_texture_table", "Texture2D<uint4>", "PF_R32G32B32A32_UINT");
            addSceneTexture(resources, symbol + "_CachedPhysMatTexture", "niagara_landscape_material", "Texture2D<uint>", "PF_R8_UINT");
        }
    }

    private static void addVirtualTextures(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("virtual_texture") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara virtual texture shader symbol " + symbol);
            if (binding.getAsJsonObject("virtual_texture").get("runtime").getAsBoolean()) {
                for (int index = 0; index < 3; index++) {
                    String prefix = symbol + "_VirtualTexture" + index;
                    addSceneTexture(resources, prefix, "niagara_texture_2d", "Texture2D", "PF_FloatRGBA");
                    addSceneTexture(resources, prefix + "PageTable", "niagara_virtual_texture_table", "Texture2D<uint4>", "PF_R32G32B32A32_UINT");
                }
            } else {
                addSceneTexture(resources, symbol + "_PhysicalTexture", "niagara_texture_2d", "Texture2D", "PF_FloatRGBA");
                for (int index = 0; index < 2; index++)
                    addSceneTexture(resources, symbol + "_PageTableTexture" + index,
                            "niagara_virtual_texture_table", "Texture2D<uint4>", "PF_R32G32B32A32_UINT");
            }
        }
    }

    private static void addDebugDrawBuffers(JsonObject stage, JsonObject program) {
        for (var element : program.getAsJsonArray("interfaces")) {
            if (!"NiagaraDataInterfaceDebugDraw".equals(element.getAsJsonObject().get("class").getAsString())) continue;
            var resources = stage.getAsJsonArray("resources");
            for (String name : List.of("RWNDIDebugDrawArgs", "RWNDIDebugDrawLineVertex")) {
                var buffer = new JsonObject();
                buffer.addProperty("name", name);
                buffer.addProperty("kind", "niagara_debug_draw_buffer");
                buffer.addProperty("original_hlsl_type", "RWBuffer<uint>");
                buffer.addProperty("hlsl_type", "RWStructuredBuffer<uint>");
                buffer.addProperty("stride_bytes", Integer.BYTES);
                buffer.addProperty("uav", true);
                resources.add(buffer);
            }
            return;
        }
    }

    private static void addParticleExportBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceExport".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara particle export shader symbol " + symbol);
            var buffer = new JsonObject();
            buffer.addProperty("name", symbol + "_RWWriteBuffer");
            buffer.addProperty("kind", "niagara_particle_export");
            buffer.addProperty("original_hlsl_type", "RWBuffer<uint>");
            buffer.addProperty("hlsl_type", "RWStructuredBuffer<uint>");
            buffer.addProperty("stride_bytes", Integer.BYTES);
            buffer.addProperty("uav", true);
            resources.add(buffer);
        }
    }

    private static void addDataChannelReadBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceDataChannelRead".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara data channel shader symbol " + symbol);
            for (var field : List.of(new MeshBuffer("ParamOffsetTable", "uint", 1),
                    new MeshBuffer("DataFloat", "float", 1), new MeshBuffer("DataInt32", "int", 1),
                    new MeshBuffer("NDCSpawnDataBuffer", "int", 1))) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field.name());
                resource.addProperty("kind", "niagara_data_channel_read");
                resource.addProperty("original_hlsl_type", "Buffer<" + field.element() + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + field.element() + ">");
                resource.addProperty("stride_bytes", Integer.BYTES);
                resources.add(resource);
            }
            for (String field : List.of("NDCInstanceCount", "NDCConsumeCount")) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field);
                resource.addProperty("kind", "niagara_data_channel_read_count");
                resource.addProperty("hlsl_type", "RWStructuredBuffer<uint>");
                resource.addProperty("stride_bytes", Integer.BYTES);
                resource.addProperty("uav", true);
                resources.add(resource);
            }
        }
    }

    private static void addDataChannelWriteBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!"NiagaraDataInterfaceDataChannelWrite".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara data channel writer shader symbol " + symbol);
            for (var field : List.of(new MeshBuffer("ParamOffsetTable", "uint", 1), new MeshBuffer("GPUBufferFloat", "float", 1),
                    new MeshBuffer("GPUBufferInt32", "int", 1), new MeshBuffer("CPUBufferFloat", "float", 1),
                    new MeshBuffer("CPUBufferInt32", "int", 1), new MeshBuffer("NDCGpuCount", "uint", 1),
                    new MeshBuffer("NDCCpuCount", "uint", 1))) {
                boolean writable = !field.name().equals("ParamOffsetTable");
                boolean count = field.name().startsWith("NDC");
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field.name());
                resource.addProperty("kind", count ? "niagara_data_channel_write_count" : "niagara_data_channel_write");
                if (!count) resource.addProperty("original_hlsl_type", (writable ? "RWBuffer<" : "Buffer<") + field.element() + ">");
                resource.addProperty("hlsl_type", (writable ? "RWStructuredBuffer<" : "StructuredBuffer<") + field.element() + ">");
                resource.addProperty("stride_bytes", Integer.BYTES);
                resource.addProperty("uav", writable);
                resources.add(resource);
            }
        }
    }

    private static void addAudioAnalysisBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            boolean spectrum = type.equals("NiagaraDataInterfaceAudioSpectrum");
            if (!binding.has("shader_symbol") || !spectrum && !type.equals("NiagaraDataInterfaceAudioOscilloscope")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara audio shader symbol " + symbol);
            var buffer = new JsonObject();
            buffer.addProperty("name", symbol + (spectrum ? "_SpectrumBuffer" : "_OscilloscopeBuffer"));
            buffer.addProperty("kind", "niagara_audio_analysis");
            buffer.addProperty("original_hlsl_type", "Buffer<float>");
            buffer.addProperty("hlsl_type", "StructuredBuffer<float>");
            buffer.addProperty("stride_bytes", Float.BYTES);
            resources.add(buffer);
        }
    }

    private static void addSimulationCacheBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("shader_symbol") || !binding.get("class").getAsString().equals("NiagaraDataInterfaceSimCacheReader")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara cache reader shader symbol " + symbol);
            var buffer = new JsonObject();
            buffer.addProperty("name", symbol + "_CacheData");
            buffer.addProperty("kind", "niagara_sim_cache");
            buffer.addProperty("original_hlsl_type", "Buffer<uint>");
            buffer.addProperty("hlsl_type", "StructuredBuffer<uint>");
            buffer.addProperty("stride_bytes", Integer.BYTES);
            resources.add(buffer);
        }
    }

    private static void addSocketBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("socket_reader") || !binding.has("shader_symbol")) continue;
            var data = new JsonObject();
            data.addProperty("name", binding.get("shader_symbol").getAsString() + "_SocketData");
            data.addProperty("kind", "niagara_socket_data");
            data.addProperty("hlsl_type", "ByteAddressBuffer");
            data.addProperty("stride_bytes", Integer.BYTES);
            resources.add(data);
        }
    }

    private static void addNeighborQueryBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("neighbor_query") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (String field : List.of("CellCount", "CellOffset", "ParticleList", "AcquireTagList",
                    "OutputCellId", "OutputParticleIdIndex", "OutputAcquireTag")) {
                boolean write = field.startsWith("Output");
                var data = new JsonObject();
                data.addProperty("name", symbol + "_" + field);
                data.addProperty("kind", "niagara_neighbor_query_buffer");
                data.addProperty("original_hlsl_type", write ? "RWBuffer<int>" : "Buffer<int>");
                data.addProperty("hlsl_type", write ? "RWStructuredBuffer<int>" : "StructuredBuffer<int>");
                data.addProperty("stride_bytes", Integer.BYTES);
                if (write) data.addProperty("uav", true);
                resources.add(data);
            }
        }
    }

    private static void addCollisionBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            boolean rigid = binding.has("rigid_mesh_collision");
            if ((!binding.has("physics_asset") && !rigid) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara collision shader symbol " + symbol);
            var fields = rigid
                    ? List.of("WorldTransformBuffer", "InverseTransformBuffer", "ElementExtentBuffer", "PhysicsTypeBuffer",
                            "MeshScaleBuffer", "DFIndexBuffer")
                    : List.of("WorldTransformBuffer", "InverseTransformBuffer", "ElementExtentBuffer", "PhysicsTypeBuffer");
            for (String field : fields) {
                boolean integer = field.equals("PhysicsTypeBuffer") || field.equals("DFIndexBuffer");
                String element = integer ? "uint" : "float4";
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field);
                resource.addProperty("kind", rigid ? "niagara_rigid_mesh_buffer" : "niagara_physics_asset_buffer");
                resource.addProperty("original_hlsl_type", "Buffer<" + element + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + element + ">");
                resource.addProperty("stride_bytes", integer ? Integer.BYTES : 4 * Float.BYTES);
                resources.add(resource);
            }
        }
    }

    private static void addHairStrandsBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("hair_strands") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (String field : List.of("DeformedPositionBuffer", "RestPositionBuffer", "CurvesOffsetsBuffer",
                    "RestTrianglePositionBuffer", "DeformedTrianglePositionBuffer", "RootBarycentricCoordinatesBuffer",
                    "RootToUniqueTriangleIndexBuffer", "DeformedPositionOffset", "BoundingBoxBuffer",
                    "RestSamplePositionsBuffer", "MeshSampleWeightsBuffer", "DeformedSamplePositionsBuffer", "ParamsScaleBuffer")) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field);
                resource.addProperty("kind", "niagara_hair_strands");
                switch (field) {
                    case "DeformedPositionBuffer", "RestPositionBuffer", "CurvesOffsetsBuffer" -> {
                        resource.addProperty("hlsl_type", field.equals("DeformedPositionBuffer") ? "RWByteAddressBuffer" : "ByteAddressBuffer");
                        resource.addProperty("stride_bytes", Integer.BYTES);
                    }
                    case "RestTrianglePositionBuffer", "DeformedTrianglePositionBuffer" -> {
                        resource.addProperty("original_hlsl_type", "Buffer<float4>");
                        resource.addProperty("hlsl_type", "StructuredBuffer<float4>");
                        resource.addProperty("stride_bytes", 4 * Float.BYTES);
                    }
                    case "RootBarycentricCoordinatesBuffer", "RootToUniqueTriangleIndexBuffer", "BoundingBoxBuffer", "ParamsScaleBuffer" -> {
                        String access = field.equals("BoundingBoxBuffer") ? "RW" : "";
                        String element = field.equals("ParamsScaleBuffer") ? "float" : "uint";
                        resource.addProperty("original_hlsl_type", access + "Buffer<" + element + ">");
                        resource.addProperty("hlsl_type", access + "StructuredBuffer<" + element + ">");
                        resource.addProperty("stride_bytes", Integer.BYTES);
                    }
                    default -> {
                        resource.addProperty("hlsl_type", "StructuredBuffer<float4>");
                        resource.addProperty("stride_bytes", 4 * Float.BYTES);
                    }
                }
                resources.add(resource);
            }
        }
    }

    private static void addChaosDestructionBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("chaos_destruction") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (var field : NiagaraChaosBufferSource.FIELDS) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field.suffix());
                resource.addProperty("kind", NiagaraChaosBufferSource.KIND);
                resource.addProperty("original_hlsl_type", "Buffer<" + field.element() + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + field.storageElement() + ">");
                resource.addProperty("stride_bytes", field.stride());
                resources.add(resource);
            }
        }
    }

    private static void addGeometryCollectionBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("geometry_collection") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (String field : List.of("WorldTransformBuffer", "PrevWorldTransformBuffer", "WorldInverseTransformBuffer",
                    "PrevWorldInverseTransformBuffer", "BoundsBuffer", "ElementTransforms")) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field);
                resource.addProperty("kind", "niagara_geometry_collection");
                if (field.equals("ElementTransforms")) {
                    resource.addProperty("hlsl_type", "ByteAddressBuffer");
                    resource.addProperty("stride_bytes", Integer.BYTES);
                } else {
                    resource.addProperty("original_hlsl_type", "Buffer<float4>");
                    resource.addProperty("hlsl_type", "StructuredBuffer<float4>");
                    resource.addProperty("stride_bytes", 4 * Float.BYTES);
                }
                resources.add(resource);
            }
        }
    }

    private static void addPhysicsFieldBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("physics_field") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (String field : List.of("ClipmapBuffer", "NodesParams", "NodesOffsets", "TargetsOffsets")) {
                String element = field.endsWith("Offsets") ? "int" : "float";
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + field);
                resource.addProperty("kind", "niagara_physics_field_buffer");
                resource.addProperty("original_hlsl_type", "Buffer<" + element + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + element + ">");
                resource.addProperty("stride_bytes", Integer.BYTES);
                resources.add(resource);
            }
        }
    }

    private static void addSplineBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("spline") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (String field : List.of("SplinePositionsLUT", "SplineScalesLUT", "SplineRotationsLUT")) {
                var data = new JsonObject();
                data.addProperty("name", symbol + "_" + field);
                data.addProperty("kind", "niagara_spline_lut");
                data.addProperty("original_hlsl_type", "Buffer<float4>");
                data.addProperty("hlsl_type", "StructuredBuffer<float4>");
                data.addProperty("stride_bytes", 4 * Float.BYTES);
                resources.add(data);
            }
        }
    }

    private static void addIntRenderTargets(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("int_render_target_2d") || !binding.has("shader_symbol")) continue;
            var image = new JsonObject();
            image.addProperty("name", binding.get("shader_symbol").getAsString() + "_RWTextureUAV");
            image.addProperty("kind", "niagara_int_render_target_2d");
            image.addProperty("hlsl_type", "RWTexture2D<int>");
            image.addProperty("format", "PF_R32_SINT");
            image.addProperty("uav", true);
            resources.add(image);
        }
    }

    private static void addDataTableBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("data_table") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var rows = new JsonObject();
            rows.addProperty("name", symbol + "_TableDataBuffer");
            rows.addProperty("kind", "niagara_table_rows");
            rows.addProperty("hlsl_type", "ByteAddressBuffer");
            rows.addProperty("stride_bytes", Integer.BYTES);
            resources.add(rows);
            var filter = new JsonObject();
            filter.addProperty("name", symbol + "_FilteredRowDataOffsetBuffer");
            filter.addProperty("kind", "niagara_table_filter");
            filter.addProperty("hlsl_type", "StructuredBuffer<uint>");
            filter.addProperty("original_hlsl_type", "Buffer<uint>");
            filter.addProperty("stride_bytes", Integer.BYTES);
            resources.add(filter);
        }
    }

    private static void addGaussianSplatBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            boolean output = binding.has("gaussian_splat_writer");
            if ((!output && !binding.has("gaussian_splat_reader")) || !binding.has("shader_symbol")) continue;
            var rows = new JsonObject();
            rows.addProperty("name", binding.get("shader_symbol").getAsString() + "_SplatData");
            rows.addProperty("kind", output ? "niagara_gaussian_splat_writer" : "niagara_gaussian_splat_reader");
            rows.addProperty("hlsl_type", output ? "RWByteAddressBuffer" : "ByteAddressBuffer");
            if (output) rows.addProperty("uav", true);
            rows.addProperty("stride_bytes", Integer.BYTES);
            resources.add(rows);
        }
    }

    private static void addIntDistributionBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("int_distribution") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var table = new JsonObject();
            table.addProperty("name", symbol + "_BuiltTableData");
            table.addProperty("kind", "niagara_int_distribution");
            table.addProperty("hlsl_type", "ByteAddressBuffer");
            table.addProperty("stride_bytes", Integer.BYTES);
            resources.add(table);
        }
    }

    private static void addMemoryBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("shader_symbol") || !binding.get("class").getAsString().equals("NiagaraDataInterfaceMemoryBuffer")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara memory buffer shader symbol " + symbol);
            var resource = new JsonObject();
            resource.addProperty("name", symbol + "_Buffer");
            resource.addProperty("kind", "niagara_memory_buffer");
            resource.addProperty("hlsl_type", "RWByteAddressBuffer");
            resource.addProperty("uav", true);
            resource.addProperty("stride_bytes", Integer.BYTES);
            resources.add(resource);
        }
    }

    private static void addDynamicMeshBuffers(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("shader_symbol") || !binding.get("class").getAsString().equals("NiagaraDataInterfaceDynamicMesh")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara dynamic mesh shader symbol " + symbol);
            for (String member : List.of("SectionBuffer", "IndexBuffer", "VertexBuffer")) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + "_" + member);
                resource.addProperty("kind", "niagara_dynamic_mesh");
                resource.addProperty("original_hlsl_type", "RWBuffer<uint>");
                resource.addProperty("hlsl_type", "RWStructuredBuffer<uint>");
                resource.addProperty("uav", true);
                resource.addProperty("stride_bytes", Integer.BYTES);
                resources.add(resource);
            }
        }
    }

    private static void addVectorFields(JsonObject stage, JsonObject program) {
        JsonObject metadata = program.getAsJsonObject("parameter_metadata");
        JsonArray interfaces = metadata == null ? null : metadata.getAsJsonArray("DataInterfaceParamInfo");
        if (interfaces == null) return;
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        for (JsonElement item : interfaces) {
            JsonObject binding = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceVectorField".equals(binding.get("DIClassName").getAsString())) continue;
            String symbol = binding.get("DataInterfaceHLSLSymbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara vector field shader symbol " + symbol);
            JsonObject texture = new JsonObject();
            texture.addProperty("name", symbol + "_Texture");
            texture.addProperty("kind", "niagara_vector_field");
            texture.addProperty("hlsl_type", "Texture3D");
            texture.addProperty("format", "PF_FloatRGBA");
            resources.add(texture);
        }
        stage.add("resources", resources);
    }

    private static void addTextures(JsonObject stage, JsonObject program) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            boolean array = type.equals("NiagaraDataInterface2DArrayTexture");
            boolean cube = type.equals("NiagaraDataInterfaceCubeTexture");
            boolean volumeCache = type.equals("NiagaraDataInterfaceVolumeCache");
            boolean volume = volumeCache || type.equals("NiagaraDataInterfaceVolumeTexture");
            if ((!array && !cube && !volume && !type.equals("NiagaraDataInterfaceTexture")) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara texture shader symbol " + symbol);
            JsonObject texture = new JsonObject();
            texture.addProperty("name", symbol + "_Texture");
            texture.addProperty("kind", array ? "niagara_texture_2d_array" : cube ? "niagara_texture_cube"
                    : volume ? "niagara_texture_volume" : "niagara_texture_2d");
            texture.addProperty("hlsl_type", array ? "Texture2DArray" : cube ? "TextureCube" : volume ? "Texture3D" : "Texture2D");
            texture.addProperty("format", volumeCache ? "PF_FloatRGBA" : "PF_R8G8B8A8");
            resources.add(texture);
        }
        stage.add("resources", resources);
    }

    private static void addSparseVolumes(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.has("shader_symbol") || !binding.get("class").getAsString().equals("NiagaraDataInterfaceSparseVolumeTexture")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new BakeException("Invalid Niagara sparse volume shader symbol " + symbol);
            addSceneTexture(resources, symbol + "_PageTableTexture", "niagara_sparse_volume_table", "Texture3D<uint>", "PF_R32_UINT");
            addSceneTexture(resources, symbol + "_PhysicalTileDataATexture", "niagara_sparse_volume_atlas", "Texture3D", "PF_FloatRGBA");
            addSceneTexture(resources, symbol + "_PhysicalTileDataBTexture", "niagara_sparse_volume_atlas", "Texture3D", "PF_FloatRGBA");
        }
    }

    private static void addRenderTargets(JsonObject stage, JsonObject program) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            boolean array = type.equals("NiagaraDataInterfaceRenderTarget2DArray");
            boolean volume = type.equals("NiagaraDataInterfaceRenderTargetVolume");
            boolean cube = type.equals("NiagaraDataInterfaceRenderTargetCube");
            if ((!array && !volume && !cube && !type.equals("NiagaraDataInterfaceRenderTarget2D")) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara render target symbol " + symbol);
            String kind = array ? "niagara_render_target_2d_array" : volume ? "niagara_render_target_volume"
                    : cube ? "niagara_render_target_cube" : "niagara_render_target_2d";
            String texture = array ? "Texture2DArray<float4>" : volume ? "Texture3D<float4>"
                    : cube ? "TextureCube<float4>" : "Texture2D<float4>";
            JsonObject input = new JsonObject();
            input.addProperty("name", symbol + "_Texture");
            input.addProperty("kind", kind);
            input.addProperty("hlsl_type", texture);
            input.addProperty("format", "PF_FloatRGBA");
            resources.add(input);
            JsonObject output = new JsonObject();
            output.addProperty("name", symbol + "_RWTexture");
            output.addProperty("kind", kind);
            output.addProperty("hlsl_type", cube ? "RWTexture2DArray<float4>" : "RW" + texture);
            output.addProperty("gpuFormat", "RGBA16_FLOAT");
            output.addProperty("dxcImageFormat", "unknown");
            output.addProperty("uav", true);
            resources.add(output);
        }
        stage.add("resources", resources);
    }

    private static void addSceneDepth(JsonObject stage, JsonObject program) {
        JsonArray interfaces = program.getAsJsonArray("interfaces");
        if (interfaces == null) return;
        boolean needsDepth = false;
        for (JsonElement item : interfaces) {
            String type = item.getAsJsonObject().get("class").getAsString();
            if (type.equals("NiagaraDataInterfaceCollisionQuery") || type.equals("NiagaraDataInterfaceOcclusion")
                    || type.equals("NiagaraDataInterfaceGBuffer")) {
                needsDepth = true;
                break;
            }
        }
        if (!needsDepth) return;
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        JsonObject depth = new JsonObject();
        depth.addProperty("name", "SceneTexturesStruct_SceneDepthTexture");
        depth.addProperty("kind", "scene_depth");
        depth.addProperty("hlsl_type", "Texture2D");
        depth.addProperty("format", "PF_DepthStencil");
        resources.add(depth);
        stage.add("resources", resources);
    }

    private static void addVelocityGrids(JsonObject stage, JsonObject program) {
        var resources = stage.getAsJsonArray("resources");
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("velocity_grid") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            for (boolean destination : new boolean[] {false, true}) {
                var resource = new JsonObject();
                resource.addProperty("name", symbol + (destination ? "_GridDestinationBuffer" : "_GridCurrentBuffer"));
                resource.addProperty("kind", "niagara_velocity_grid_buffer");
                String access = destination ? "RW" : "";
                resource.addProperty("original_hlsl_type", access + "Buffer<int>");
                resource.addProperty("hlsl_type", access + "StructuredBuffer<int>");
                resource.addProperty("stride_bytes", Integer.BYTES);
                if (destination) resource.addProperty("uav", true);
                resources.add(resource);
            }
        }
    }

    private static void addRasterizationGrids(JsonObject stage, JsonObject program) {
        var interfaces = program.getAsJsonArray("interfaces");
        if (interfaces == null) return;
        var resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        for (var item : interfaces) {
            var binding = item.getAsJsonObject();
            if (!binding.has("shader_symbol") || !binding.get("class").getAsString().equals("NiagaraDataInterfaceRasterizationGrid3D"))
                continue;
            String symbol = binding.get("shader_symbol").getAsString();
            addSceneTexture(resources, symbol + "_IntGrid", "niagara_rasterization_grid", "Texture3D<int>", "PF_R32_SINT");
            var output = new JsonObject();
            output.addProperty("name", symbol + "_OutputIntGrid");
            output.addProperty("kind", "niagara_rasterization_grid");
            output.addProperty("hlsl_type", "RWTexture3D<int>");
            output.addProperty("format", "PF_R32_SINT");
            output.addProperty("uav", true);
            resources.add(output);

            var attributes = new JsonObject();
            attributes.addProperty("name", symbol + "_PerAttributeData");
            attributes.addProperty("kind", "niagara_rasterization_attributes");
            attributes.addProperty("original_hlsl_type", "Buffer<float4>");
            attributes.addProperty("hlsl_type", "StructuredBuffer<float4>");
            attributes.addProperty("stride_bytes", 16);
            resources.add(attributes);
        }
        if (!resources.isEmpty()) stage.add("resources", resources);
    }

    private static void addOcclusionScene(JsonObject stage, JsonObject program) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        boolean occlusion = false;
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if (!binding.get("class").getAsString().equals("NiagaraDataInterfaceOcclusion") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara occlusion shader symbol " + symbol);
            addSceneTexture(resources, symbol + "_CloudVolumetricTexture", "niagara_occlusion_scene", "Texture2D<float4>", "PF_FloatRGBA");
            occlusion = true;
        }
        if (occlusion) addSceneTexture(resources, "View_TransmittanceLutTexture", "niagara_occlusion_scene", "Texture2D<float4>", "PF_FloatRGBA");
        stage.add("resources", resources);
    }

    private static void addGBufferScene(JsonObject stage, JsonObject program) {
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        boolean found = false;
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            if (type.equals("NiagaraDataInterfaceCollisionQuery")) {
                found = true;
                continue;
            }
            if (!type.equals("NiagaraDataInterfaceGBuffer")) continue;
            found = true;
            if (!binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara GBuffer shader symbol " + symbol);
            addSceneTexture(resources, symbol + "_VelocityTexture", "niagara_gbuffer_color", "Texture2D", "PF_FloatRGBA");
        }
        if (!found) return;
        // The host publishes UE's legacy deferred scene texture layout to Niagara.
        var defines = stage.getAsJsonObject("defines");
        defines.addProperty("SHADING_PATH_DEFERRED", 1);
        defines.addProperty("FEATURE_LEVEL", "FEATURE_LEVEL_SM5");
        defines.addProperty("SCENE_TEXTURES_DISABLED", 0);
        defines.addProperty("SUBSTRATE_ENABLED", 0);
        defines.addProperty("SUBSTRATE_GBUFFER_FORMAT", 0);
        for (String name : List.of("SceneColorTexture", "GBufferATexture", "GBufferBTexture", "GBufferCTexture",
                "GBufferDTexture", "GBufferETexture", "GBufferFTexture", "GBufferVelocityTexture", "GBufferSGGXTexture", "ScreenSpaceAOTexture"))
            addSceneTexture(resources, "SceneTexturesStruct_" + name, "niagara_gbuffer_color", "Texture2D", "PF_FloatRGBA");
        for (String name : List.of("ScenePartialDepthTexture", "CustomDepthTexture"))
            addSceneTexture(resources, "SceneTexturesStruct_" + name, "scene_depth", "Texture2D", "PF_DepthStencil");
        addSceneTexture(resources, "SceneTexturesStruct_CustomStencilTexture", "niagara_gbuffer_stencil", "Texture2D<uint2>", "PF_R8G8_UINT");
        stage.add("resources", resources);
    }

    private static void addGlobalDistanceField(JsonObject stage, JsonObject program) {
        for (var element : program.getAsJsonArray("interfaces")) {
            if (!element.getAsJsonObject().get("class").getAsString().equals("NiagaraDataInterfaceCollisionQuery")) continue;
            var resources = stage.getAsJsonArray("resources");
            for (String name : List.of("PageAtlas", "CoverageAtlas", "Mip"))
                addSceneTexture(resources, "GlobalDistanceField" + name + "Texture", "niagara_distance_field", "Texture3D", "PF_R8");
            addSceneTexture(resources, "GlobalDistanceFieldPageTableTexture", "niagara_distance_field_page_table", "Texture3D<uint>", "PF_R32_UINT");
            return;
        }
    }

    private static void addSceneTexture(JsonArray resources, String name, String kind, String hlsl, String format) {
        var texture = new JsonObject();
        texture.addProperty("name", name);
        texture.addProperty("kind", kind);
        texture.addProperty("hlsl_type", hlsl);
        texture.addProperty("format", format);
        resources.add(texture);
    }

    private static void addParticleReaders(JsonObject stage, JsonObject program, Path shaderSource) {
        JsonArray interfaces = program.getAsJsonArray("interfaces");
        if (interfaces == null) return;
        final String source;
        try { source = Files.readString(shaderSource, StandardCharsets.UTF_8); }
        catch (IOException error) { throw new BakeException("Cannot read Niagara particle reader declarations", error); }
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        JsonArray halves = new JsonArray();
        for (JsonElement item : interfaces) {
            JsonObject binding = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceParticleRead".equals(binding.get("class").getAsString()) || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            if (!symbol.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new BakeException("Invalid Niagara particle reader symbol " + symbol);
            for (String suffix : List.of("SpawnedIDsBuffer", "IDToIndexTable", "InputFloatBuffer", "InputIntBuffer", "InputHalfBuffer")) {
                String name = symbol + "_" + suffix;
                String type = suffix.equals("InputFloatBuffer") ? "float" : suffix.equals("InputHalfBuffer") ? "half" : "int";
                var declaration = java.util.regex.Pattern.compile("\\bBuffer\\s*<\\s*" + type + "\\s*>\\s+"
                        + java.util.regex.Pattern.quote(name) + "\\b").matcher(source);
                if (!declaration.find()) continue;
                if (declaration.find()) throw new BakeException("Ambiguous Niagara particle reader buffer " + name);
                JsonObject resource = new JsonObject();
                resource.addProperty("name", name);
                resource.addProperty("kind", "niagara_particle_read_buffer");
                resource.addProperty("original_hlsl_type", "Buffer<" + type + ">");
                resource.addProperty("hlsl_type", "StructuredBuffer<" + (type.equals("half") ? "uint" : type) + ">");
                resource.addProperty("stride_bytes", 4);
                resources.add(resource);
                if (type.equals("half")) halves.add(name);
            }
        }
        stage.add("resources", resources);
        if (!halves.isEmpty()) stage.add("niagara_particle_read_half_buffers", halves);
    }

    private static Set<String> writableArrays(JsonArray emitters) {
        Set<String> result = new HashSet<>();
        for (JsonElement item : emitters) {
            JsonObject emitter = item.getAsJsonObject();
            JsonObject gpu = emitter.has("gpu_program") ? emitter.getAsJsonObject("gpu_program") : null;
            if (gpu == null || !gpu.has("interfaces")) continue;
            for (JsonElement element : gpu.getAsJsonArray("interfaces")) {
                JsonObject binding = element.getAsJsonObject();
                if (binding.has("array_writable") && binding.get("array_writable").getAsBoolean()
                        && binding.has("interface_id")) result.add(binding.get("interface_id").getAsString());
            }
        }
        return result;
    }

    private static void addArrayBuffers(JsonObject stage, JsonObject program, Path shaderSource, Set<String> writableArrays) {
        final String source;
        try { source = Files.readString(shaderSource, StandardCharsets.UTF_8); }
        catch (IOException error) { throw new BakeException("Cannot read Niagara array shader declarations", error); }
        JsonArray interfaces = program.getAsJsonArray("interfaces");
        if (interfaces == null) return;
        JsonArray resources = stage.has("resources") ? stage.getAsJsonArray("resources") : new JsonArray();
        Set<String> classes = Set.of("NiagaraDataInterfaceArrayFloat", "NiagaraDataInterfaceArrayFloat2",
                "NiagaraDataInterfaceArrayFloat3", "NiagaraDataInterfaceArrayFloat4", "NiagaraDataInterfaceArrayPosition",
                "NiagaraDataInterfaceArrayColor", "NiagaraDataInterfaceArrayQuat", "NiagaraDataInterfaceArrayMatrix",
                "NiagaraDataInterfaceArrayInt32", "NiagaraDataInterfaceArrayUInt8", "NiagaraDataInterfaceArrayBool",
                "NiagaraDataInterfaceArrayNiagaraID");
        JsonArray uint8Writes = new JsonArray();
        JsonArray sharedReads = new JsonArray();
        for (JsonElement item : interfaces) {
            JsonObject binding = item.getAsJsonObject();
            if (!binding.has("shader_symbol")) continue;
            String interfaceClass = binding.get("class").getAsString();
            String symbol = binding.get("shader_symbol").getAsString();
            if (interfaceClass.equals("NiagaraDataInterfaceNeighborGrid3D")) {
                addNeighborGridBuffers(source, resources, symbol);
                continue;
            }
            if (!classes.contains(interfaceClass)) continue;
            boolean sharedWritable = binding.has("interface_id") && writableArrays.contains(binding.get("interface_id").getAsString());
            for (String suffix : List.of("_ArrayReadBuffer", "_ArrayRWBuffer")) {
                String name = symbol + suffix;
                var match = java.util.regex.Pattern.compile("\\b(RWBuffer|Buffer)\\s*<\\s*(float4|float2|float|int|uint)\\s*>\\s+"
                        + java.util.regex.Pattern.quote(name) + "\\b").matcher(source);
                if (!match.find()) continue;
                String original = match.group(1), type = match.group(2);
                if (match.find()) throw new BakeException("Ambiguous Niagara array buffer declaration " + name);
                boolean writable = original.equals("RWBuffer");
                if (!writable && sharedWritable && suffix.equals("_ArrayReadBuffer")) sharedReads.add(symbol);
                if (writable && binding.get("class").getAsString().equals("NiagaraDataInterfaceArrayUInt8"))
                    uint8Writes.add(name);
                JsonObject resource = new JsonObject();
                resource.addProperty("name", name);
                resource.addProperty("kind", "niagara_array_buffer");
                resource.addProperty("original_hlsl_type", original + "<" + type + ">");
                resource.addProperty("hlsl_type", (writable ? "RWStructuredBuffer<" : "StructuredBuffer<") + type + ">");
                resource.addProperty("stride_bytes", type.equals("float2") ? 8 : type.equals("float4") ? 16 : 4);
                resource.addProperty("uav", writable);
                resources.add(resource);
            }
        }
        stage.add("resources", resources);
        if (!uint8Writes.isEmpty()) stage.add("niagara_uint8_array_writes", uint8Writes);
        if (!sharedReads.isEmpty()) stage.add("niagara_shared_array_readers", sharedReads);
    }

    private static void addNeighborGridBuffers(String source, JsonArray resources, String symbol) {
        for (String suffix : List.of("_ParticleNeighbors", "_ParticleNeighborCount", "_OutputParticleNeighbors", "_OutputParticleNeighborCount")) {
            String name = symbol + suffix;
            boolean writable = suffix.startsWith("_Output");
            String original = writable ? "RWBuffer" : "Buffer";
            var declaration = java.util.regex.Pattern.compile("\\b" + original + "\\s*<\\s*int\\s*>\\s+"
                    + java.util.regex.Pattern.quote(name) + "\\b").matcher(source);
            if (!declaration.find()) continue;
            if (declaration.find()) throw new BakeException("Ambiguous Niagara Neighbor Grid3D resource " + name);
            JsonObject resource = new JsonObject();
            resource.addProperty("name", name);
            resource.addProperty("kind", "niagara_neighbor_grid_buffer");
            resource.addProperty("original_hlsl_type", original + "<int>");
            resource.addProperty("hlsl_type", (writable ? "RWStructuredBuffer" : "StructuredBuffer") + "<int>");
            resource.addProperty("stride_bytes", Integer.BYTES);
            resource.addProperty("uav", writable);
            resources.add(resource);
        }
    }
}
