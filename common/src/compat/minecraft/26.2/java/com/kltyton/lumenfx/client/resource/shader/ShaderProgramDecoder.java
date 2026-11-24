package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeAccess;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderCapabilities;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.mojang.blaze3d.GpuFormat;

/** Decodes the offline descriptor ABI without reading resources or creating GPU objects. */
final class ShaderProgramDecoder {
    private ShaderProgramDecoder() { }

    static ShaderProgram decode(JsonObject metadata, byte[] vulkan, byte[] opengl) {
        return decodeStage(text(metadata, "name") + "/" + text(metadata.getAsJsonObject("permutation"), "id"),
                metadata, vulkan, opengl);
    }

    static ShaderProgram decodeStage(String name, JsonObject metadata, byte[] vulkan, byte[] opengl) {
        JsonObject workgroup = metadata.getAsJsonObject("workgroup");
        JsonObject capabilities = metadata.getAsJsonObject("capabilities");
        if (capabilities.has("storage16") && flag(capabilities, "storage16"))
            throw new IllegalArgumentException("The reference shader profile has unexpected 16-bit storage: " + name);
        if (capabilities.has("storageImageReadWithoutFormat") && flag(capabilities, "storageImageReadWithoutFormat"))
            throw new IllegalArgumentException("Compute shader requires a typed storage-image read contract: " + name);
        ShaderProgram.Builder builder = ShaderProgram.builder(name)
                .vulkanSpirv(vulkan).openglSpirv(opengl)
                .vulkanEntryPoint(text(metadata, "vulkanEntryPoint")).openglEntryPoint(text(metadata, "openglEntryPoint"))
                .capabilities(new ShaderCapabilities(flag(capabilities, "waveOperations"),
                        flag(capabilities, "nativeFp16"), flag(capabilities, "storageImageExtendedFormats"),
                        flag(capabilities, "storageBuffers"), capabilities.has("storageImageWriteWithoutFormat")
                                && flag(capabilities, "storageImageWriteWithoutFormat")));
        switch (text(metadata, "stage")) {
            case "compute" -> builder.workgroup(number(workgroup, "x"), number(workgroup, "y"), number(workgroup, "z"));
            case "vertex", "fragment" -> builder.workgroup(1, 1, 1);
            default -> throw new IllegalArgumentException("Unknown native shader stage for " + name);
        }
        for (var element : metadata.getAsJsonArray("bindings")) {
            JsonObject binding = element.getAsJsonObject();
            String bindingName = text(binding, "name");
            int set = number(binding, "descriptorSet");
            int descriptor = number(binding, "descriptorBinding");
            int gl = number(binding, "glBinding");
            builder.binding(switch (text(binding, "kind")) {
                case "uniformBuffer" -> new ShaderBinding.UniformBuffer(bindingName, set, descriptor, gl);
                case "sampledTexture" -> new ShaderBinding.SampledTexture(bindingName, format(binding),
                        ShaderBinding.DescriptorForm.valueOf(text(binding, "descriptorForm")),
                        flag(binding, "array"), binding.has("volume") && flag(binding, "volume"),
                        set, descriptor, number(binding, "samplerDescriptorBinding"), gl,
                        binding.has("dynamicFloatFormat") && flag(binding, "dynamicFloatFormat"),
                        binding.has("cube") && flag(binding, "cube"));
                case "storageImage" -> new ShaderBinding.StorageImage(bindingName, format(binding),
                        ComputeAccess.valueOf(text(binding, "access")), flag(binding, "array"), set, descriptor, gl,
                        binding.has("dynamicFloatFormat") && flag(binding, "dynamicFloatFormat"),
                        binding.has("volume") && flag(binding, "volume"));
                case "storageBuffer" -> new ShaderBinding.StorageBuffer(bindingName,
                        ComputeAccess.valueOf(text(binding, "access")), set, descriptor, gl,
                        binding.has("openGlTexelView") ? openGlTexelView(binding.getAsJsonObject("openGlTexelView")) : null);
                default -> throw new IllegalArgumentException("Unknown native shader descriptor kind for " + bindingName);
            });
        }
        return builder.build();
    }

    private static ShaderBinding.OpenGlTexelView openGlTexelView(JsonObject value) {
        return new ShaderBinding.OpenGlTexelView(number(value, "unit"), format(value));
    }

    private static GpuFormat format(JsonObject object) { return GpuFormat.valueOf(text(object, "format")); }
    private static String text(JsonObject object, String field) { return object.get(field).getAsString(); }
    private static int number(JsonObject object, String field) { return object.get(field).getAsInt(); }
    private static boolean flag(JsonObject object, String field) { return object.get(field).getAsBoolean(); }
}
