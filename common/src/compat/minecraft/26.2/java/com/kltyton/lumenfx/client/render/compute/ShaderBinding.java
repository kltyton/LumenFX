package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Exact shader-visible binding metadata produced by the offline manifest.
 *
 * <p>{@code descriptorSet}/{@code descriptorBinding} are the SPIR-V/Vulkan numbers. {@code
 * glBinding} is the GL binding point, texture unit, image unit or block binding selected for the
 * OpenGL-environment SPIR-V. The descriptor set is zero because the host Vulkan path uses
 * {@code VK_KHR_push_descriptor} for set 0.</p>
 */
public sealed interface ShaderBinding permits ShaderBinding.UniformBuffer,
        ShaderBinding.SampledTexture, ShaderBinding.StorageImage, ShaderBinding.StorageBuffer {
    String name();

    int descriptorSet();

    int descriptorBinding();

    int glBinding();

    record UniformBuffer(String name, int descriptorSet, int descriptorBinding, int glBinding)
            implements ShaderBinding {
        public UniformBuffer {
            validate(name, descriptorSet, descriptorBinding, glBinding);
        }
    }

    enum DescriptorForm {
        COMBINED_IMAGE_SAMPLER,
        SEPARATE_IMAGE_AND_SAMPLER,
        SAMPLED_IMAGE
    }

    record SampledTexture(
            String name,
            GpuFormat format,
            DescriptorForm form,
            boolean array,
            boolean volume,
            int descriptorSet,
            int descriptorBinding,
            int samplerDescriptorBinding,
            int glBinding,
            boolean dynamicFloatFormat,
            boolean cube
    ) implements ShaderBinding {
        public SampledTexture(String name, GpuFormat format, DescriptorForm form, boolean array,
                              int descriptorSet, int descriptorBinding, int samplerDescriptorBinding, int glBinding) {
            this(name, format, form, array, false, descriptorSet, descriptorBinding, samplerDescriptorBinding, glBinding, false);
        }
        public SampledTexture(String name, GpuFormat format, DescriptorForm form, boolean array, boolean volume,
                              int descriptorSet, int descriptorBinding, int samplerDescriptorBinding, int glBinding) {
            this(name, format, form, array, volume, descriptorSet, descriptorBinding, samplerDescriptorBinding, glBinding, false);
        }
        public SampledTexture(String name, GpuFormat format, DescriptorForm form, boolean array, boolean volume,
                              int descriptorSet, int descriptorBinding, int samplerDescriptorBinding, int glBinding,
                              boolean dynamicFloatFormat) {
            this(name, format, form, array, volume, descriptorSet, descriptorBinding, samplerDescriptorBinding, glBinding,
                    dynamicFloatFormat, false);
        }
        public SampledTexture {
            validate(name, descriptorSet, descriptorBinding, glBinding);
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(form, "form");
            if (volume && (array || cube))
                throw new IllegalArgumentException("Conflicting sampled texture dimensions: " + name);
            if (dynamicFloatFormat && !com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.floatingColor(format))
                throw new IllegalArgumentException("Dynamic float image format requires a floating color sampler: " + name);
            if (form == DescriptorForm.COMBINED_IMAGE_SAMPLER || form == DescriptorForm.SAMPLED_IMAGE) {
                if (samplerDescriptorBinding != -1) {
                    throw new IllegalArgumentException(
                            form + " " + name + " must not declare a sampler binding"
                    );
                }
            } else if (samplerDescriptorBinding < 0 || samplerDescriptorBinding == descriptorBinding) {
                throw new IllegalArgumentException(
                        "Separate image/sampler " + name + " needs a distinct sampler binding"
                );
            }
        }

        public boolean accepts(GpuFormat actual) {
            return dynamicFloatFormat ? com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.floatingColor(actual) : actual == format;
        }
    }

    record StorageImage(
            String name,
            GpuFormat format,
            ComputeAccess access,
            boolean array,
            int descriptorSet,
            int descriptorBinding,
            int glBinding,
            boolean dynamicFloatFormat,
            boolean volume
    ) implements ShaderBinding {
        public StorageImage(String name, GpuFormat format, ComputeAccess access, boolean array,
                            int descriptorSet, int descriptorBinding, int glBinding) {
            this(name, format, access, array, descriptorSet, descriptorBinding, glBinding, false);
        }
        public StorageImage(String name, GpuFormat format, ComputeAccess access, boolean array,
                            int descriptorSet, int descriptorBinding, int glBinding, boolean dynamicFloatFormat) {
            this(name, format, access, array, descriptorSet, descriptorBinding, glBinding, dynamicFloatFormat, false);
        }
        public StorageImage {
            validate(name, descriptorSet, descriptorBinding, glBinding);
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(access, "access");
            if (array && volume) throw new IllegalArgumentException("A storage volume cannot also be an array: " + name);
            if (dynamicFloatFormat && (access != ComputeAccess.WRITE_ONLY
                    || !com.kltyton.lumenfx.client.render.compute.format.StorageImageFormats.floatingColor(format)))
                throw new IllegalArgumentException("Formatless floating storage requires a write-only color image: " + name);
        }

        public boolean accepts(GpuFormat actual) {
            return dynamicFloatFormat
                    ? com.kltyton.lumenfx.client.render.compute.format.StorageImageFormats.floatingColor(actual)
                    : actual == format;
        }
    }

    record OpenGlTexelView(int unit, GpuFormat format) {
        public OpenGlTexelView {
            if (unit < 0) throw new IllegalArgumentException("Negative OpenGL texel view unit");
            Objects.requireNonNull(format, "format");
        }
    }

    record StorageBuffer(
            String name,
            ComputeAccess access,
            int descriptorSet,
            int descriptorBinding,
            int glBinding,
            @Nullable OpenGlTexelView openGlTexelView
    ) implements ShaderBinding {
        public StorageBuffer(String name, ComputeAccess access, int descriptorSet, int descriptorBinding, int glBinding) {
            this(name, access, descriptorSet, descriptorBinding, glBinding, null);
        }
        public StorageBuffer {
            validate(name, descriptorSet, descriptorBinding, glBinding);
            Objects.requireNonNull(access, "access");
            if (openGlTexelView != null && access != ComputeAccess.READ_ONLY)
                throw new IllegalArgumentException("OpenGL texel views require read-only storage: " + name);
        }
    }

    private static void validate(
            final String name,
            final int descriptorSet,
            final int descriptorBinding,
            final int glBinding
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Compute binding name must not be blank");
        }
        if (descriptorSet != 0) {
            throw new IllegalArgumentException(
                    "Vulkan push descriptors require descriptor set 0 for binding " + name
            );
        }
        if (descriptorBinding < 0 || glBinding < 0) {
            throw new IllegalArgumentException("Negative binding index for " + name);
        }
    }
}
