package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ShaderMetadataBuilder {
    private ShaderMetadataBuilder() {
    }

    static JsonObject stage(
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final ShaderManifest.Stage stage,
            final ShaderManifest.Variant variant,
            final ShaderStage shaderStage,
            final SpirvReflection.Reflection reflection,
            final BindingPlanner.Plan plan,
            final Map<String, Object> artifacts,
            final JsonObject clipContract
    ) {
        final JsonObject json = new JsonObject();
        json.addProperty("stage", shaderStage.id());
        json.addProperty("entrypoint", stage.entrypoint());
        json.addProperty("source", stage.source());
        if (shaderStage == ShaderStage.COMPUTE) {
            final JsonObject workgroup = new JsonObject();
            workgroup.addProperty("x", reflection.localSize()[0]);
            workgroup.addProperty("y", reflection.localSize()[1]);
            workgroup.addProperty("z", reflection.localSize()[2]);
            json.add("workgroup", workgroup);
        }
        if (clipContract != null) {
            json.add("graphicsClipContract", clipContract);
        }
        final JsonObject capabilities = new JsonObject();
        capabilities.addProperty("waveOperations", reflection.hasWaveOperations());
        capabilities.addProperty("nativeFp16", reflection.hasNativeFp16());
        capabilities.addProperty("storage16", reflection.hasStorage16());
        capabilities.addProperty("subgroupExtended", reflection.hasSubgroupExtended());
        capabilities.addProperty("storageImageExtendedFormats", reflection.hasStorageImageExtendedFormats());
        capabilities.addProperty("storageImageWriteWithoutFormat", reflection.hasStorageImageWriteWithoutFormat());
        capabilities.addProperty("storageImageReadWithoutFormat", reflection.hasStorageImageReadWithoutFormat());
        capabilities.addProperty("storageBuffers", reflection.hasStorageBuffers());
        json.add("capabilities", capabilities);

        final JsonArray bindings = new JsonArray();
        for (BindingPlanner.PlanBinding binding : plan.bindings()) {
            JsonObject descriptor = binding.toJson();
            if ("storageImage".equals(binding.kind()) && reflection.storageImages().stream().anyMatch(resource ->
                    resource.name().equals(binding.sourceName()) && resource.imageDimension() == org.lwjgl.util.spvc.Spv.SpvDim3D))
                descriptor.addProperty("volume", true);
            if ("storageImage".equals(binding.kind()) && ShaderManifest.resources(pass).stream().anyMatch(resource ->
                    resource.name().equals(binding.sourceName()) && "unknown".equals(resource.dxcImageFormat()))) {
                descriptor.addProperty("dynamicFloatFormat", true);
            }
            bindings.add(withStage(descriptor, shaderStage));
        }
        for (BindingPlanner.SampledBinding binding : plan.sampledBindings()) {
            JsonObject sampled = binding.toJson();
            if (ShaderManifest.resources(pass).stream().anyMatch(resource -> resource.name().equals(binding.imageName())
                    && ("niagara_texture_2d".equals(resource.kind()) || "niagara_render_target_2d".equals(resource.kind())
                    || "niagara_texture_cube".equals(resource.kind())
                    || "niagara_texture_volume".equals(resource.kind())
                    || "niagara_render_target_volume".equals(resource.kind())
                    || "niagara_render_target_cube".equals(resource.kind())
                    || "niagara_occlusion_scene".equals(resource.kind())
                    || "niagara_distance_field".equals(resource.kind())
                    || "niagara_mesh_distance_field_atlas".equals(resource.kind())
                    || "niagara_sparse_volume_atlas".equals(resource.kind())
                    || NiagaraGrid3DShaderFormats.IMAGE_KIND.equals(resource.kind())
                    || NiagaraGrid3DShaderFormats.ARRAY_IMAGE_KIND.equals(resource.kind())
                    || "niagara_gbuffer_color".equals(resource.kind())
                    || "niagara_texture_2d_array".equals(resource.kind()) || "niagara_render_target_2d_array".equals(resource.kind())
                    || "texture_target_mips".equals(resource.kind()))))
                sampled.addProperty("dynamicFloatFormat", true);
            bindings.add(withStage(sampled, shaderStage));
        }
        json.add("bindings", bindings);

        final JsonArray samplers = new JsonArray();
        for (BindingPlanner.SamplerPlan sampler : plan.samplers()) {
            final JsonObject samplerJson = sampler.toJson();
            for (ShaderManifest.Sampler declared : pass.samplers()) {
                if (declared.name().equals(sampler.name())) {
                    if (declared.filter() != null) {
                        samplerJson.addProperty("filter", declared.filter());
                    }
                    if (declared.address() != null) {
                        samplerJson.addProperty("address", declared.address());
                    }
                    break;
                }
            }
            samplers.add(samplerJson);
        }
        json.add("samplers", samplers);

        final JsonArray pairs = new JsonArray();
        for (BindingPlanner.SamplerPair pair : plan.samplerPairs()) {
            pairs.add(pair.toJson());
        }
        json.add("samplerPairs", pairs);

        final JsonObject artifactsJson = new JsonObject();
        for (Map.Entry<String, Object> artifact : artifacts.entrySet()) {
            artifactsJson.add(artifact.getKey(), new com.google.gson.Gson().toJsonTree(artifact.getValue()));
        }
        json.add("artifacts", artifactsJson);

        final JsonObject reflectionJson = new JsonObject();
        final JsonArray localSize = new JsonArray();
        for (int value : reflection.localSize()) {
            localSize.add(value);
        }
        reflectionJson.add("localSize", localSize);
        final JsonArray capabilityArray = new JsonArray();
        reflection.capabilities().stream().sorted().forEach(capabilityArray::add);
        reflectionJson.add("capabilities", capabilityArray);
        final JsonArray uniformBuffers = new JsonArray();
        for (SpirvReflection.UniformBuffer buffer : reflection.uniformLayouts()) {
            uniformBuffers.add(buffer(buffer));
        }
        reflectionJson.add("uniformBuffers", uniformBuffers);
        final JsonArray storageBuffers = new JsonArray();
        for (SpirvReflection.UniformBuffer buffer : reflection.storageBufferLayouts()) {
            storageBuffers.add(buffer(buffer));
        }
        reflectionJson.add("storageBuffers", storageBuffers);
        final JsonArray storageImages = new JsonArray();
        for (SpirvReflection.Resource image : reflection.storageImages()) {
            final JsonObject imageJson = new JsonObject();
            imageJson.addProperty("name", image.name());
            imageJson.addProperty("descriptorSet", image.descriptorSet());
            imageJson.addProperty("descriptorBinding", image.descriptorBinding());
            imageJson.addProperty("array", image.imageArrayed());
            imageJson.addProperty("imageStorageFormat", image.imageStorageFormat());
            storageImages.add(imageJson);
        }
        reflectionJson.add("storageImages", storageImages);
        reflectionJson.add("stageInputs", stageIo(reflection.stageInputs()));
        reflectionJson.add("stageOutputs", stageIo(reflection.stageOutputs()));
        json.add("reflection", reflectionJson);

        json.addProperty("pipelineVariant", variant.id());
        json.addProperty("pipelinePass", pass.name());
        json.addProperty("engineHead", manifest.engineHead());
        return json;
    }

    static JsonObject program(
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final ShaderManifest.Variant variant,
            final List<JsonObject> stages,
            final JsonObject clipContract,
            final JsonObject artifacts
    ) {
        final JsonObject json = new JsonObject();
        json.addProperty("schema", "lumenfx.shaders.program.v1");
        json.addProperty("pipeline", manifest.pipeline());
        json.addProperty("name", pass.name());
        json.addProperty("pass", pass.name());
        json.addProperty("engineHead", manifest.engineHead());
        if (pass.condition() != null) {
            json.addProperty("sourceCondition", pass.condition());
        }
        final JsonObject variantJson = new JsonObject();
        variantJson.addProperty("id", variant.id());
        final JsonObject defines = new JsonObject();
        variant.defines().forEach(defines::addProperty);
        variantJson.add("defines", defines);
        final JsonArray keys = new JsonArray();
        for (String key : variant.selectionKeys()) {
            keys.add(key);
        }
        variantJson.add("selectionKeys", keys);
        if (variant.selector() != null) {
            variantJson.add("selector", variant.selector());
        }
        json.add("variant", variantJson);
        final boolean graphics = stages.stream().anyMatch(stage -> !"compute".equals(
                ShaderManifest.string(stage, "stage")
        ));
        json.addProperty("stageClass", graphics ? "graphics" : "compute");
        if (pass.raw().has("state")) {
            json.add("sourceState", pass.raw().get("state").deepCopy());
        }
        if (pass.raw().has("niagara_stage")) {
            json.add("niagaraStage", pass.raw().get("niagara_stage").deepCopy());
            json.add("niagaraStageMetadata", pass.raw().get("niagara_stage_metadata").deepCopy());
            json.add("niagaraSourceSha256", pass.raw().get("niagara_source_sha256").deepCopy());
        }
        if (pass.raw().has("niagara_grid_collection")) json.add("niagaraGridCollection", pass.raw().get("niagara_grid_collection").deepCopy());
        final JsonArray abiLowering = new JsonArray();
        if (pass.raw().has("texture_mip_bounds") && pass.raw().get("texture_mip_bounds").getAsBoolean()) {
            JsonObject bounds = new JsonObject();
            bounds.addProperty("scope", "RenderCore 2D mip destination");
            bounds.addProperty("sourceSemantics", "SM5 out-of-image UAV stores have no effect");
            bounds.addProperty("preservesInBoundsShader", true);
            abiLowering.add(bounds);
        }
        for (ShaderManifest.Resource resource : ShaderManifest.resources(pass)) {
            String loweringSpec = resource.niagaraBufferLowering();
            if (loweringSpec != null) {
                final JsonObject lowering = new JsonObject();
                lowering.addProperty("resource", resource.name());
                String fromType = resource.originalHlslType();
                String toType = resource.hlslType();
                if ((fromType == null || toType == null) && loweringSpec.contains(":")) {
                    int sep = loweringSpec.indexOf(':');
                    String mode = loweringSpec.substring(0, sep);
                    String elem = loweringSpec.substring(sep + 1);
                    boolean isWrite = "write".equals(mode);
                    if (fromType == null) fromType = (isWrite ? "RWBuffer<" : "Buffer<") + elem + ">";
                    if (toType == null) toType = (isWrite ? "RWStructuredBuffer<" : "StructuredBuffer<") + elem + ">";
                }
                lowering.addProperty("from", fromType);
                lowering.addProperty("to", toType);
                lowering.addProperty("elementStride", resource.strideBytes());
                lowering.addProperty("scope", "Niagara GPU particle buffer");
                abiLowering.add(lowering);
            }
        }
        if (pass.raw().has("niagara_packed_half") && pass.raw().get("niagara_packed_half").getAsBoolean()) {
            for (String name : List.of("InputHalf", "RWOutputHalf")) {
                final JsonObject lowering = new JsonObject();
                lowering.addProperty("resource", name);
                lowering.addProperty("from", name.equals("InputHalf") ? "Buffer<half>" : "RWBuffer<half>");
                lowering.addProperty("to", name.equals("InputHalf") ? "StructuredBuffer<uint>" : "RWStructuredBuffer<uint>");
                lowering.addProperty("packing", "two binary16 elements per uint32");
                lowering.addProperty("writes", name.equals("InputHalf") ? "read-only" : "atomic compare-exchange preserving adjacent half");
                if (name.equals("RWOutputHalf")) lowering.addProperty("partialReads", "atomic read preserving adjacent half");
                abiLowering.add(lowering);
            }
        }
        if (pass.raw().has("niagara_sort_packed_half") && pass.raw().get("niagara_sort_packed_half").getAsBoolean()) {
            final JsonObject lowering = new JsonObject();
            lowering.addProperty("resource", "NiagaraParticleDataHalf");
            lowering.addProperty("from", "Buffer<half>");
            lowering.addProperty("to", "StructuredBuffer<uint>");
            lowering.addProperty("packing", "two binary16 elements per uint32");
            abiLowering.add(lowering);
        }
        if (pass.raw().has("niagara_ribbon_packed_half") && pass.raw().get("niagara_ribbon_packed_half").getAsBoolean()) {
            final JsonObject lowering = new JsonObject();
            lowering.addProperty("resource", "NiagaraParticleDataHalf");
            lowering.addProperty("from", "Buffer<float> bound as R16F");
            lowering.addProperty("to", "StructuredBuffer<uint>");
            lowering.addProperty("packing", "two binary16 elements per uint32");
            abiLowering.add(lowering);
        }
        if (pass.raw().has("niagara_uint8_array_writes")) {
            final JsonObject lowering = new JsonObject();
            lowering.addProperty("scope", "Niagara R8_UINT array writes");
            lowering.addProperty("storage", "32-bit elements with R8 truncation");
            lowering.add("resources", pass.raw().getAsJsonArray("niagara_uint8_array_writes").deepCopy());
            abiLowering.add(lowering);
        }
        if (pass.raw().has("niagara_shared_array_readers")) {
            final JsonObject lowering = new JsonObject();
            lowering.addProperty("scope", "Niagara shared array reads");
            lowering.addProperty("length", "GPU instance counter");
            lowering.add("interfaces", pass.raw().getAsJsonArray("niagara_shared_array_readers").deepCopy());
            abiLowering.add(lowering);
        }
        if (pass.raw().has("niagara_particle_read_half_buffers")) {
            final JsonObject lowering = new JsonObject();
            lowering.addProperty("scope", "Niagara particle reader half buffers");
            lowering.addProperty("from", "Buffer<half>");
            lowering.addProperty("to", "StructuredBuffer<uint>");
            lowering.addProperty("packing", "two binary16 elements per uint32");
            lowering.add("resources", pass.raw().getAsJsonArray("niagara_particle_read_half_buffers").deepCopy());
            abiLowering.add(lowering);
        }
        json.add("abiLowering", abiLowering);
        final JsonArray sourceResources = new JsonArray();
        for (String field : List.of("srvs", "uavs", "buffers", "resources", "samplers")) {
            final com.google.gson.JsonArray resources = pass.raw().getAsJsonArray(field);
            if (resources != null) {
                for (com.google.gson.JsonElement resource : resources) {
                    sourceResources.add(resource.deepCopy());
                }
            }
        }
        json.add("sourceResources", sourceResources);
        if (pass.raw().has("include_overrides"))
            json.add("passIncludeOverrides", pass.raw().getAsJsonArray("include_overrides").deepCopy());
        final com.google.gson.JsonArray parameters = pass.raw().getAsJsonArray("uniforms_extra");
        if (parameters != null) {
            json.add("sourceParameters", parameters.deepCopy());
        }
        final JsonArray stageArray = new JsonArray();
        for (JsonObject stage : stages) {
            stageArray.add(stage);
        }
        json.add("stages", stageArray);
        if (clipContract != null) {
            json.add("graphicsClipContract", clipContract);
        }
        final JsonArray bindings = new JsonArray();
        for (JsonObject stage : stages) {
            for (com.google.gson.JsonElement binding : stage.getAsJsonArray("bindings")) {
                bindings.add(binding.deepCopy());
            }
        }
        json.add("bindings", bindings);
        json.add("artifacts", artifacts);
        return json;
    }

    private static JsonObject withStage(final JsonObject binding, final ShaderStage stage) {
        final JsonObject copy = binding.deepCopy();
        copy.addProperty("stage", stage.id());
        return copy;
    }

    private static JsonObject buffer(final SpirvReflection.UniformBuffer buffer) {
        final JsonObject json = new JsonObject();
        json.addProperty("name", GlslTranslator.canonicalBlockName(buffer.name()));
        json.addProperty("sourceName", buffer.name());
        json.addProperty("descriptorSet", buffer.descriptorSet());
        json.addProperty("descriptorBinding", buffer.descriptorBinding());
        json.addProperty("size", buffer.size());
        json.add("members", members(buffer.members()));
        return json;
    }

    private static JsonArray members(final List<SpirvReflection.Member> members) {
        final JsonArray array = new JsonArray();
        for (SpirvReflection.Member member : members) {
            final JsonObject json = new JsonObject();
            json.addProperty("name", member.name());
            json.addProperty("type", member.type());
            json.addProperty("baseType", member.baseType());
            json.addProperty("bitWidth", member.bitWidth());
            json.addProperty("vectorSize", member.vectorSize());
            json.addProperty("columns", member.columns());
            final JsonArray dimensions = new JsonArray();
            for (int dimension : member.arrayDimensions()) {
                dimensions.add(dimension);
            }
            json.add("arrayDimensions", dimensions);
            json.addProperty("offset", member.offset());
            json.addProperty("size", member.size());
            if (member.arrayStride() != 0) {
                json.addProperty("arrayStride", member.arrayStride());
            }
            if (member.matrixStride() != 0) {
                json.addProperty("matrixStride", member.matrixStride());
            }
            json.addProperty("matrixOrder", member.matrixOrder());
            json.addProperty("rowMajor", member.rowMajor());
            json.add("members", members(member.members()));
            array.add(json);
        }
        return array;
    }

    private static JsonArray stageIo(final List<SpirvReflection.StageIo> io) {
        final JsonArray array = new JsonArray();
        for (SpirvReflection.StageIo value : io) {
            final JsonObject json = new JsonObject();
            json.addProperty("name", value.name());
            json.addProperty("location", value.location());
            json.addProperty("type", value.type());
            json.addProperty("baseType", value.baseType());
            json.addProperty("bitWidth", value.bitWidth());
            json.addProperty("vectorSize", value.vectorSize());
            json.addProperty("columns", value.columns());
            final JsonArray dimensions = new JsonArray();
            for (int dimension : value.arrayDimensions()) {
                dimensions.add(dimension);
            }
            json.add("arrayDimensions", dimensions);
            array.add(json);
        }
        return array;
    }

    static List<JsonObject> copyStages(final List<JsonObject> stages) {
        return new ArrayList<>(stages);
    }
}
