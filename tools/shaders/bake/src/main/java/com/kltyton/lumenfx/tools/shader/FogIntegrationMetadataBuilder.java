package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;

final class FogIntegrationMetadataBuilder {
    static final String SCHEMA = "lumenfx.fog_integration.v1";
    static final String NAME = "VolumetricFogIntegrate";
    static final int WORKGROUP_X = 8;
    static final int WORKGROUP_Y = 8;
    static final int WORKGROUP_Z = 1;
    static final int FOG_VOLUME_BYTES = 416;
    static final int FOG_VOLUME_BINDING = 0;
    static final int SCATTERING_BINDING = 1;
    static final int OUTPUT_BINDING = 2;
    static final int DESCRIPTOR_COUNT = 3;
    static final String FOG_VOLUME_NAME = "FogVolume";
    static final String SCATTERING_NAME = "ScatteringSampler";
    static final String OUTPUT_NAME = "IntegratedOutput";
    static final String INPUT_FORMAT = "RGBA16_FLOAT";
    static final String OUTPUT_FORMAT = "RGBA16_FLOAT";
    static final String OUTPUT_ACCESS = "WRITE_ONLY";

    private FogIntegrationMetadataBuilder() {
    }

    static JsonObject build(
            final SpirvReflection.Reflection vulkan,
            final SpirvReflection.Reflection opengl,
            final ShaderArtifact vulkanArtifact,
            final ShaderArtifact openglArtifact,
            final FogSourceStager.StagedSource staged
    ) {
        verify(vulkan, "Vulkan");
        verify(opengl, "OpenGL");
        verifyEquivalent(vulkan, opengl);

        final JsonObject shader = new JsonObject();
        shader.addProperty("name", NAME);
        shader.addProperty("stage", "compute");
        shader.addProperty("vulkanEntryPoint", "main");
        shader.addProperty("openglEntryPoint", "main");
        shader.add("workgroup", workgroup(vulkan.localSize()));
        shader.add("capabilities", capabilities(vulkan));

        final JsonArray bindings = new JsonArray();
        bindings.add(uniformBinding(vulkan, opengl));
        bindings.add(sampledBinding(vulkan, opengl));
        bindings.add(storageImageBinding(vulkan, opengl));
        shader.add("bindings", bindings);

        final JsonObject artifacts = new JsonObject();
        artifacts.add("vulkan", vulkanArtifact.toJson());
        artifacts.add("opengl", openglArtifact.toJson());

        final JsonObject reflection = new JsonObject();
        reflection.add("vulkan", reflectionJson(vulkan));
        reflection.add("opengl", reflectionJson(opengl));

        final JsonObject capabilities = new JsonObject();
        capabilities.add("vulkan", capabilities(vulkan));
        capabilities.add("opengl", capabilities(opengl));

        final JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("name", NAME);
        root.add("shader", shader);
        root.add("artifacts", artifacts);
        root.add("capabilities", capabilities);
        root.add("reflection", reflection);
        root.add("contract", contract());
        root.add("sourceProvenance", provenance(staged));
        return root;
    }

    private static void verify(final SpirvReflection.Reflection reflection, final String binary) {
        final int[] localSize = reflection.localSize();
        if (localSize.length != 3
                || localSize[0] != WORKGROUP_X
                || localSize[1] != WORKGROUP_Y
                || localSize[2] != WORKGROUP_Z) {
            throw new BakeException(
                    binary + " LocalSize " + java.util.Arrays.toString(localSize)
                            + " does not match the required " + WORKGROUP_X + "x" + WORKGROUP_Y + "x" + WORKGROUP_Z
            );
        }
        if (reflection.hasWaveOperations()) {
            throw new BakeException(binary + " fog integration unexpectedly requires wave operations");
        }
        if (reflection.hasNativeFp16() || reflection.hasStorage16()) {
            throw new BakeException(binary + " fog integration unexpectedly requires 16-bit types or storage");
        }
        if (reflection.hasStorageBuffers()) {
            throw new BakeException(binary + " fog integration unexpectedly declares storage buffers");
        }

        if (reflection.uniformBuffers().size() != 1 || reflection.uniformLayouts().size() != 1) {
            throw new BakeException(binary + " fog integration must declare exactly one uniform buffer");
        }
        final SpirvReflection.UniformBuffer buffer = reflection.uniformLayouts().get(0);
        final String bufferName = GlslTranslator.canonicalBlockName(buffer.name());
        if (!FOG_VOLUME_NAME.equals(bufferName)) {
            throw new BakeException(binary + " uniform buffer is " + bufferName + ", expected " + FOG_VOLUME_NAME);
        }
        if (buffer.size() != FOG_VOLUME_BYTES) {
            throw new BakeException(
                    binary + " " + FOG_VOLUME_NAME + " is " + buffer.size()
                            + " bytes, expected " + FOG_VOLUME_BYTES
            );
        }
        requireBinding(buffer.name(), buffer.descriptorSet(), buffer.descriptorBinding(),
                FOG_VOLUME_BINDING, binary + " uniform buffer");

        if (!reflection.storageBuffers().isEmpty()) {
            throw new BakeException(binary + " fog integration must not declare storage buffers");
        }
        if (reflection.sampledImages().size() != 1 || !reflection.separateImages().isEmpty()) {
            throw new BakeException(binary + " fog integration must declare exactly one combined sampled image");
        }
        final SpirvReflection.Resource sampled = reflection.sampledImages().get(0);
        if (!SCATTERING_NAME.equals(sampled.name())) {
            throw new BakeException(binary + " sampled image is " + sampled.name() + ", expected " + SCATTERING_NAME);
        }
        requireBinding(sampled.name(), sampled.descriptorSet(), sampled.descriptorBinding(),
                SCATTERING_BINDING, binary + " sampled image");
        if (sampled.imageDimension() != Spv.SpvDim2D || sampled.imageArrayed() || sampled.imageDepth()) {
            throw new BakeException(binary + " " + SCATTERING_NAME + " must be a non-array, non-depth 2D image");
        }
        if (sampled.baseType() != Spvc.SPVC_BASETYPE_SAMPLED_IMAGE) {
            throw new BakeException(binary + " " + SCATTERING_NAME + " is not a combined image sampler");
        }
        if (!reflection.separateSamplers().isEmpty()) {
            throw new BakeException(binary + " " + SCATTERING_NAME + " requires separate sampler descriptors");
        }

        if (reflection.storageImages().size() != 1) {
            throw new BakeException(binary + " fog integration must declare exactly one storage image");
        }
        final SpirvReflection.Resource output = reflection.storageImages().get(0);
        if (!OUTPUT_NAME.equals(output.name())) {
            throw new BakeException(binary + " storage image is " + output.name() + ", expected " + OUTPUT_NAME);
        }
        requireBinding(output.name(), output.descriptorSet(), output.descriptorBinding(),
                OUTPUT_BINDING, binary + " storage image");
        if (output.imageDimension() != Spv.SpvDim2D || output.imageArrayed()) {
            throw new BakeException(binary + " " + OUTPUT_NAME + " must be a non-array 2D image");
        }
        if (output.imageStorageFormat() != Spv.SpvImageFormatRgba16f) {
            throw new BakeException(
                    binary + " " + OUTPUT_NAME + " reflects image format "
                            + output.imageStorageFormat() + ", expected rgba16f ("
                            + Spv.SpvImageFormatRgba16f + ")"
            );
        }
        if (!OUTPUT_ACCESS.equals(access(output, binary + " " + OUTPUT_NAME))) {
            throw new BakeException(binary + " " + OUTPUT_NAME + " is not write-only");
        }

        final int descriptors = reflection.uniformBuffers().size()
                + reflection.storageBuffers().size()
                + reflection.sampledImages().size()
                + reflection.separateImages().size()
                + reflection.separateSamplers().size()
                + reflection.storageImages().size();
        if (descriptors != DESCRIPTOR_COUNT) {
            throw new BakeException(
                    binary + " fog integration reflects " + descriptors
                            + " descriptors, expected " + DESCRIPTOR_COUNT
            );
        }
    }

    private static void verifyEquivalent(
            final SpirvReflection.Reflection vulkan,
            final SpirvReflection.Reflection opengl
    ) {
        if (!java.util.Arrays.equals(vulkan.localSize(), opengl.localSize())) {
            throw new BakeException(
                    "OpenGL LocalSize " + java.util.Arrays.toString(opengl.localSize())
                            + " differs from Vulkan LocalSize " + java.util.Arrays.toString(vulkan.localSize())
            );
        }
        final SpirvReflection.UniformBuffer vkBuffer = vulkan.uniformLayouts().get(0);
        final SpirvReflection.UniformBuffer glBuffer = opengl.uniformLayouts().get(0);
        if (!GlslTranslator.canonicalBlockName(vkBuffer.name()).equals(GlslTranslator.canonicalBlockName(glBuffer.name()))
                || vkBuffer.size() != glBuffer.size()
                || vkBuffer.descriptorSet() != glBuffer.descriptorSet()
                || vkBuffer.descriptorBinding() != glBuffer.descriptorBinding()) {
            throw new BakeException("OpenGL FogVolume layout/binding differs from Vulkan");
        }
        final SpirvReflection.Resource vkSampled = vulkan.sampledImages().get(0);
        final SpirvReflection.Resource glSampled = opengl.sampledImages().get(0);
        if (!vkSampled.name().equals(glSampled.name())
                || vkSampled.descriptorSet() != glSampled.descriptorSet()
                || vkSampled.descriptorBinding() != glSampled.descriptorBinding()
                || vkSampled.imageDimension() != glSampled.imageDimension()
                || vkSampled.imageArrayed() != glSampled.imageArrayed()) {
            throw new BakeException("OpenGL ScatteringSampler differs from Vulkan");
        }
        final SpirvReflection.Resource vkOutput = vulkan.storageImages().get(0);
        final SpirvReflection.Resource glOutput = opengl.storageImages().get(0);
        if (!vkOutput.name().equals(glOutput.name())
                || vkOutput.descriptorSet() != glOutput.descriptorSet()
                || vkOutput.descriptorBinding() != glOutput.descriptorBinding()
                || vkOutput.imageDimension() != glOutput.imageDimension()
                || vkOutput.imageStorageFormat() != glOutput.imageStorageFormat()
                || vkOutput.nonWritable() != glOutput.nonWritable()
                || vkOutput.nonReadable() != glOutput.nonReadable()) {
            throw new BakeException("OpenGL IntegratedOutput differs from Vulkan");
        }
    }

    private static void requireBinding(
            final String name,
            final int descriptorSet,
            final int descriptorBinding,
            final int expectedBinding,
            final String description
    ) {
        if (descriptorSet != 0 || descriptorBinding != expectedBinding) {
            throw new BakeException(
                    description + " " + name + " reflects set " + descriptorSet
                            + " binding " + descriptorBinding + ", expected set 0 binding " + expectedBinding
            );
        }
    }

    private static String access(final SpirvReflection.Resource image, final String description) {
        if (image.nonWritable() && image.nonReadable()) {
            throw new BakeException(description + " is both non-writable and non-readable");
        }
        if (image.nonWritable()) {
            return "READ_ONLY";
        }
        if (image.nonReadable()) {
            return "WRITE_ONLY";
        }
        return switch (image.imageAccess()) {
            case Spv.SpvAccessQualifierReadOnly -> "READ_ONLY";
            case Spv.SpvAccessQualifierWriteOnly -> "WRITE_ONLY";
            case Spv.SpvAccessQualifierReadWrite -> "READ_WRITE";
            case Spv.SpvAccessQualifierMax -> throw new BakeException(
                    description + " has no reflected image access qualifier"
            );
            default -> throw new BakeException(
                    description + " has unknown image access qualifier " + image.imageAccess()
            );
        };
    }

    private static JsonObject uniformBinding(
            final SpirvReflection.Reflection vulkan,
            final SpirvReflection.Reflection opengl
    ) {
        final SpirvReflection.UniformBuffer buffer = vulkan.uniformLayouts().get(0);
        final SpirvReflection.UniformBuffer glBuffer = opengl.uniformLayouts().get(0);
        final JsonObject json = new JsonObject();
        json.addProperty("kind", "uniformBuffer");
        json.addProperty("name", GlslTranslator.canonicalBlockName(buffer.name()));
        json.addProperty("descriptorSet", buffer.descriptorSet());
        json.addProperty("descriptorBinding", buffer.descriptorBinding());
        json.addProperty("glBinding", glBuffer.descriptorBinding());
        return json;
    }

    private static JsonObject sampledBinding(
            final SpirvReflection.Reflection vulkan,
            final SpirvReflection.Reflection opengl
    ) {
        final SpirvReflection.Resource image = vulkan.sampledImages().get(0);
        final SpirvReflection.Resource glImage = opengl.sampledImages().get(0);
        final JsonObject json = new JsonObject();
        json.addProperty("kind", "sampledTexture");
        json.addProperty("name", image.name());
        json.addProperty("format", INPUT_FORMAT);
        json.addProperty("descriptorForm", "COMBINED_IMAGE_SAMPLER");
        json.addProperty("array", image.imageArrayed());
        json.addProperty("descriptorSet", image.descriptorSet());
        json.addProperty("descriptorBinding", image.descriptorBinding());
        json.addProperty("samplerDescriptorBinding", -1);
        json.addProperty("glBinding", glImage.descriptorBinding());
        return json;
    }

    private static JsonObject storageImageBinding(
            final SpirvReflection.Reflection vulkan,
            final SpirvReflection.Reflection opengl
    ) {
        final SpirvReflection.Resource image = vulkan.storageImages().get(0);
        final SpirvReflection.Resource glImage = opengl.storageImages().get(0);
        final JsonObject json = new JsonObject();
        json.addProperty("kind", "storageImage");
        json.addProperty("name", image.name());
        json.addProperty("format", OUTPUT_FORMAT);
        json.addProperty("access", access(image, OUTPUT_NAME));
        json.addProperty("array", image.imageArrayed());
        json.addProperty("descriptorSet", image.descriptorSet());
        json.addProperty("descriptorBinding", image.descriptorBinding());
        json.addProperty("glBinding", glImage.descriptorBinding());
        return json;
    }

    private static JsonObject workgroup(final int[] localSize) {
        final JsonObject workgroup = new JsonObject();
        workgroup.addProperty("x", localSize[0]);
        workgroup.addProperty("y", localSize[1]);
        workgroup.addProperty("z", localSize[2]);
        return workgroup;
    }

    private static JsonObject capabilities(final SpirvReflection.Reflection reflection) {
        final JsonObject capabilities = new JsonObject();
        capabilities.addProperty("waveOperations", reflection.hasWaveOperations());
        capabilities.addProperty("nativeFp16", reflection.hasNativeFp16());
        capabilities.addProperty("storageImageExtendedFormats", reflection.hasStorageImageExtendedFormats());
        capabilities.addProperty("storageBuffers", reflection.hasStorageBuffers());
        capabilities.addProperty("storage16", reflection.hasStorage16());
        return capabilities;
    }

    private static JsonObject contract() {
        final JsonObject contract = new JsonObject();
        contract.add("workgroup", workgroup(new int[]{WORKGROUP_X, WORKGROUP_Y, WORKGROUP_Z}));
        contract.addProperty("uniformBufferBytes", FOG_VOLUME_BYTES);
        contract.addProperty("descriptorCount", DESCRIPTOR_COUNT);
        contract.addProperty("inputFormat", INPUT_FORMAT);
        contract.addProperty("outputFormat", OUTPUT_FORMAT);
        contract.addProperty("outputAccess", OUTPUT_ACCESS);
        contract.addProperty("vulkanVersion", "1.0");
        contract.addProperty("openglVersion", "4.5");
        return contract;
    }

    private static JsonObject provenance(final FogSourceStager.StagedSource staged) {
        final List<String> imports = new ArrayList<>();
        for (String path : staged.provenance()) {
            if (!staged.entryName().equals(path) && !imports.contains(path)) {
                imports.add(path);
            }
        }
        imports.sort(Comparator.naturalOrder());
        final JsonArray importArray = new JsonArray();
        for (String path : imports) {
            importArray.add(path);
        }
        final JsonObject provenance = new JsonObject();
        provenance.addProperty("base", "shader-root");
        provenance.addProperty("entry", staged.entryName());
        provenance.add("imports", importArray);
        provenance.addProperty("importNamespace", "lumenfx");
        provenance.addProperty("engineSources", false);
        provenance.addProperty("expandedSha256",
                ShaderOutputWriter.sha256(staged.flattenedSource().getBytes(StandardCharsets.UTF_8)));
        return provenance;
    }

    private static JsonObject reflectionJson(final SpirvReflection.Reflection reflection) {
        final JsonObject json = new JsonObject();
        final JsonArray localSize = new JsonArray();
        for (int value : reflection.localSize()) {
            localSize.add(value);
        }
        json.add("localSize", localSize);

        final JsonArray capabilityArray = new JsonArray();
        reflection.capabilities().stream().sorted().forEach(capabilityArray::add);
        json.add("capabilities", capabilityArray);

        final JsonObject capabilitySet = new JsonObject();
        capabilitySet.addProperty("waveOperations", reflection.hasWaveOperations());
        capabilitySet.addProperty("nativeFp16", reflection.hasNativeFp16());
        capabilitySet.addProperty("float16", reflection.capabilities().contains(Spv.SpvCapabilityFloat16));
        capabilitySet.addProperty("storage16", reflection.hasStorage16());
        capabilitySet.addProperty("subgroupExtended", reflection.hasSubgroupExtended());
        capabilitySet.addProperty("storageImageExtendedFormats", reflection.hasStorageImageExtendedFormats());
        capabilitySet.addProperty("storageBuffers", reflection.hasStorageBuffers());
        json.add("capabilitySet", capabilitySet);

        final JsonArray uniformBuffers = new JsonArray();
        for (SpirvReflection.UniformBuffer buffer : reflection.uniformLayouts()) {
            final JsonObject bufferJson = new JsonObject();
            bufferJson.addProperty("name", GlslTranslator.canonicalBlockName(buffer.name()));
            if (!buffer.name().equals(GlslTranslator.canonicalBlockName(buffer.name()))) {
                bufferJson.addProperty("sourceName", buffer.name());
            }
            bufferJson.addProperty("descriptorSet", buffer.descriptorSet());
            bufferJson.addProperty("descriptorBinding", buffer.descriptorBinding());
            bufferJson.addProperty("size", buffer.size());
            bufferJson.add("members", members(buffer.members()));
            uniformBuffers.add(bufferJson);
        }
        json.add("uniformBuffers", uniformBuffers);

        final JsonArray storageBuffers = new JsonArray();
        for (SpirvReflection.UniformBuffer buffer : reflection.storageBufferLayouts()) {
            final JsonObject bufferJson = new JsonObject();
            bufferJson.addProperty("name", buffer.name());
            bufferJson.addProperty("descriptorSet", buffer.descriptorSet());
            bufferJson.addProperty("descriptorBinding", buffer.descriptorBinding());
            bufferJson.addProperty("size", buffer.size());
            bufferJson.add("members", members(buffer.members()));
            storageBuffers.add(bufferJson);
        }
        json.add("storageBuffers", storageBuffers);

        final JsonArray sampledImages = new JsonArray();
        for (SpirvReflection.Resource image : reflection.sampledImages()) {
            sampledImages.add(imageJson(image, false));
        }
        json.add("sampledImages", sampledImages);

        final JsonArray storageImages = new JsonArray();
        for (SpirvReflection.Resource image : reflection.storageImages()) {
            storageImages.add(imageJson(image, true));
        }
        json.add("storageImages", storageImages);

        final JsonArray separateSamplers = new JsonArray();
        for (SpirvReflection.Resource sampler : reflection.separateSamplers()) {
            final JsonObject samplerJson = new JsonObject();
            samplerJson.addProperty("name", sampler.name());
            samplerJson.addProperty("descriptorSet", sampler.descriptorSet());
            samplerJson.addProperty("descriptorBinding", sampler.descriptorBinding());
            separateSamplers.add(samplerJson);
        }
        json.add("separateSamplers", separateSamplers);

        json.add("stageInputs", stageIo(reflection.stageInputs()));
        json.add("stageOutputs", stageIo(reflection.stageOutputs()));
        return json;
    }

    private static JsonObject imageJson(final SpirvReflection.Resource image, final boolean storage) {
        final JsonObject json = new JsonObject();
        json.addProperty("name", image.name());
        json.addProperty("descriptorSet", image.descriptorSet());
        json.addProperty("descriptorBinding", image.descriptorBinding());
        json.addProperty("array", image.imageArrayed());
        json.addProperty("dimension", image.imageDimension());
        json.addProperty("imageStorageFormat", image.imageStorageFormat());
        final String format = imageFormat(image.imageStorageFormat());
        if (format != null) {
            json.addProperty("imageFormat", format);
        }
        json.addProperty("nonWritable", image.nonWritable());
        json.addProperty("nonReadable", image.nonReadable());
        if (storage) {
            json.addProperty("access", access(image, image.name()));
        }
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

    private static String imageFormat(final int format) {
        return switch (format) {
            case 1 -> "rgba32f";
            case 2 -> "rgba16f";
            case 3 -> "r32f";
            case 4 -> "rgba8";
            case 5 -> "rgba8_snorm";
            case 6 -> "rg32f";
            case 7 -> "rg16f";
            case 8 -> "r11g11b10f";
            case 9 -> "r16f";
            case 10 -> "rgba16";
            case 11 -> "rgb10a2";
            case 12 -> "rg16";
            case 13 -> "rg8";
            case 14 -> "r16";
            case 15 -> "r8";
            case 16 -> "rgba16_snorm";
            case 17 -> "rg16_snorm";
            case 18 -> "rg8_snorm";
            case 19 -> "r16_snorm";
            case 20 -> "r8_snorm";
            case 21 -> "rgba32i";
            case 22 -> "rgba16i";
            case 23 -> "rgba8i";
            case 24 -> "r32i";
            case 25 -> "rg32i";
            case 26 -> "rg16i";
            case 27 -> "rg8i";
            case 28 -> "r16i";
            case 29 -> "r8i";
            case 30 -> "rgba32ui";
            case 31 -> "rgba16ui";
            case 32 -> "rgba8ui";
            case 33 -> "r32ui";
            case 34 -> "rgb10a2ui";
            case 35 -> "rg32ui";
            case 36 -> "rg16ui";
            case 37 -> "rg8ui";
            case 38 -> "r16ui";
            case 39 -> "r8ui";
            default -> null;
        };
    }
}
