package com.kltyton.lumenfx.client.render.compute;

import com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Properties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/** Vulkan compute pipeline: host command buffer, host queue, KHR push descriptors. */
final class VulkanComputeKernel extends ComputeKernel {
    private final VulkanDevice vk;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long shaderModule;
    private final long pipeline;
    private boolean closed;

    VulkanComputeKernel(final GpuDevice device, final VulkanDevice vk, final ShaderProgram program) {
        super(program);
        RenderSystem.assertOnRenderThread();
        this.vk = vk;
        verifyCapabilities(vk, program);

        long dsl = 0L;
        long layout = 0L;
        long module = 0L;
        long pipe = 0L;
        try {
            dsl = createDescriptorSetLayout(vk, program);
            layout = createPipelineLayout(vk, dsl);
            module = createShaderModule(vk, program);
            pipe = createPipeline(vk, program, layout, module);
        } catch (RuntimeException error) {
            destroy(vk, pipe, layout, dsl, module);
            throw error;
        }
        this.descriptorSetLayout = dsl;
        this.pipelineLayout = layout;
        this.shaderModule = module;
        this.pipeline = pipe;
    }

    @Override
    public void dispatch(final ComputeBindings bindings, final int width, final int height, final int depth) {
        if (this.closed) {
            throw new IllegalStateException("Compute kernel is closed: " + this.program().name());
        }
        Objects.requireNonNull(bindings, "bindings");
        final int groupsX = groups(width, this.program().workgroupX());
        final int groupsY = groups(height, this.program().workgroupY());
        final int groupsZ = groups(depth, this.program().workgroupZ());

        final VkCommandBuffer commandBuffer = this.vk.createCommandEncoder().textureInitCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            recordBindings(commandBuffer, bindings, stack);
            VK12.vkCmdDispatch(commandBuffer, groupsX, groupsY, groupsZ);
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
        }
    }

    @Override
    protected void dispatchIndirectInternal(final ComputeBindings bindings, final GpuBufferSlice args) {
        if (this.closed) {
            throw new IllegalStateException("Compute kernel is closed: " + this.program().name());
        }
        final VkCommandBuffer commandBuffer = this.vk.createCommandEncoder().textureInitCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            recordBindings(commandBuffer, bindings, stack);
            VK12.vkCmdDispatchIndirect(
                    commandBuffer,
                    ((VulkanGpuBuffer) args.buffer()).vkBuffer(),
                    args.offset()
            );
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
        }
    }

    @Override public void dispatchIndependentBatch(List<Dispatch> requests) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Compute kernel is closed: " + program().name());
        if (requests.isEmpty()) return;
        VkCommandBuffer commandBuffer = vk.createCommandEncoder().textureInitCommandBuffer();
        try (MemoryStack boundary = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commandBuffer, boundary);
            try {
                for (Dispatch request : requests) try (MemoryStack stack = MemoryStack.stackPush()) {
                    recordBindings(commandBuffer, request.bindings(), stack);
                    VK12.vkCmdDispatch(commandBuffer, groups(request.width(), program().workgroupX()),
                            groups(request.height(), program().workgroupY()), groups(request.depth(), program().workgroupZ()));
                }
            } finally { VulkanCommandEncoder.memoryBarrier(commandBuffer, boundary); }
        }
    }

    private void recordBindings(
            final VkCommandBuffer commandBuffer,
            final ComputeBindings bindings,
            final MemoryStack stack
    ) {
        VK12.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
        if (this.program().bindings().isEmpty()) {
            return;
        }
        final int writeCount = descriptorEntryCount(this.program().bindings());
        final VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
        final Set<Integer> writtenImages = new HashSet<>();
        final Map<Integer, Long> sharedImages = new HashMap<>();
        final Set<Integer> writtenSamplers = new HashSet<>();
        final Map<Integer, Long> sharedSamplers = new HashMap<>();
        int index = 0;
        for (ShaderBinding binding : this.program().bindings()) {
            index = write(
                    binding, bindings, stack, writes, index,
                    writtenImages, sharedImages, writtenSamplers, sharedSamplers
            );
        }
        writes.position(0);
        KHRPushDescriptor.vkCmdPushDescriptorSetKHR(
                commandBuffer,
                VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                this.pipelineLayout,
                0,
                writes
        );
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        this.closed = true;
        final VulkanDevice device = this.vk;
        final long pipeline = this.pipeline;
        final long pipelineLayout = this.pipelineLayout;
        final long descriptorSetLayout = this.descriptorSetLayout;
        final long shaderModule = this.shaderModule;
        device.createCommandEncoder().queueForDestroy(
                () -> destroy(device, pipeline, pipelineLayout, descriptorSetLayout, shaderModule)
        );
    }

    private int write(
            final ShaderBinding binding,
            final ComputeBindings bindings,
            final MemoryStack stack,
            final VkWriteDescriptorSet.Buffer writes,
            final int index,
            final Set<Integer> writtenImages,
            final Map<Integer, Long> sharedImages,
            final Set<Integer> writtenSamplers,
            final Map<Integer, Long> sharedSamplers
    ) {
        switch (binding) {
            case ShaderBinding.UniformBuffer uniform -> {
                final ComputeBindings.Uniform value = require(bindings.uniform(uniform.name()), uniform.name());
                final GpuBufferSlice slice = value.slice();
                checkSlice(slice, uniform.name());
                if ((slice.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) {
                    throw new IllegalArgumentException("Buffer lacks USAGE_UNIFORM for " + uniform.name());
                }
                final VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                info.buffer(((VulkanGpuBuffer) slice.buffer()).vkBuffer());
                info.offset(slice.offset());
                info.range(slice.length());
                final VkWriteDescriptorSet set = writeSet(
                        stack, uniform.descriptorBinding(), VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                );
                set.pBufferInfo(info);
                putWrite(writes, index, set);
                return index + 1;
            }
            case ShaderBinding.SampledTexture texture -> {
                final ComputeBindings.Sampled value = require(bindings.sampled(texture.name()), texture.name());
                final long imageView;
                final GpuSampler sampler;
                final long volumeSampler;
                switch (value) {
                    case ComputeBindings.Texture host -> {
                        if (host.view().isClosed()) {
                            throw new IllegalStateException("Closed texture view for " + texture.name());
                        }
                        final boolean cube = (host.view().texture().usage()
                                & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
                        final boolean cubeArray = SampledTextureStorage.isCubeArray(host.view().texture());
                        final boolean resourceVolume = host.view().texture() instanceof VolumeGpuTexture;
                        final boolean resourceArray = cubeArray || !resourceVolume && !cube && host.view().texture().getDepthOrLayers() > 1;
                        if (resourceVolume != texture.volume() || resourceArray != texture.array()
                                || texture.cube() && !cube || cubeArray && !texture.cube()) {
                            throw new IllegalArgumentException(
                                    "Texture dimensionality mismatch for " + texture.name()
                                            + ": shader expects array=" + texture.array() + ", cube=" + texture.cube()
                                            + "; use a compute-native binding for a one-layer 2D array"
                            );
                        }
                        if (!com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.accepts(texture, host.view().texture())) {
                            throw new IllegalArgumentException(
                                    "Texture format mismatch for " + texture.name() + ": shader expects "
                                            + texture.format() + ", resource is "
                                            + com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.describe(host.view().texture())
                            );
                        }
                        imageView = ((VulkanGpuTextureView) host.view()).vkImageView();
                        sampler = host.sampler();
                        volumeSampler = 0;
                    }
                    case ComputeBindings.SampledImage host -> {
                        if (host.view().isClosed()) {
                            throw new IllegalStateException("Closed texture view for " + texture.name());
                        }
                        final boolean cube = (host.view().texture().usage()
                                & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
                        final boolean cubeArray = SampledTextureStorage.isCubeArray(host.view().texture());
                        final boolean resourceVolume = host.view().texture() instanceof VolumeGpuTexture;
                        final boolean resourceArray = cubeArray || !resourceVolume && !cube && host.view().texture().getDepthOrLayers() > 1;
                        if (resourceVolume != texture.volume() || resourceArray != texture.array()
                                || texture.cube() && !cube || cubeArray && !texture.cube()) {
                            throw new IllegalArgumentException(
                                    "Texture dimensionality mismatch for " + texture.name()
                                            + ": shader expects array=" + texture.array() + ", cube=" + texture.cube()
                                            + "; use a compute-native binding for a one-layer 2D array"
                            );
                        }
                        if (!com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.accepts(texture, host.view().texture())) {
                            throw new IllegalArgumentException(
                                    "Texture format mismatch for " + texture.name() + ": shader expects "
                                            + texture.format() + ", resource is "
                                            + com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.describe(host.view().texture())
                            );
                        }
                        imageView = ((VulkanGpuTextureView) host.view()).vkImageView();
                        sampler = null;
                        volumeSampler = 0;
                    }
                    case ComputeBindings.NativeTexture nativeTexture -> {
                        if (texture.volume()) throw new IllegalArgumentException("Expected volume texture for " + texture.name());
                        final ComputeTexture nativeImage = nativeTexture.texture();
                        if (nativeImage.isClosed()) {
                            throw new IllegalStateException("Closed compute texture for " + texture.name());
                        }
                        if (nativeTexture.arrayView() != texture.array()) {
                            throw new IllegalArgumentException(
                                    "Sampled view dimensionality mismatch for " + texture.name()
                                            + ": shader expects array=" + texture.array()
                                            + ", view array=" + nativeTexture.arrayView()
                            );
                        }
                        if (!com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.accepts(texture, nativeImage.texture())) {
                            throw new IllegalArgumentException(
                                    "Texture format mismatch for " + texture.name() + ": shader expects "
                                            + texture.format() + ", resource is " + nativeImage.format()
                            );
                        }
                        imageView = nativeTexture.sampledView().vkImageView();
                        sampler = nativeTexture.sampler();
                        volumeSampler = 0;
                    }
                    case ComputeBindings.NativeSampledImage nativeTexture -> {
                        if (texture.volume()) throw new IllegalArgumentException("Expected volume texture for " + texture.name());
                        final ComputeTexture nativeImage = nativeTexture.texture();
                        if (nativeImage.isClosed()) {
                            throw new IllegalStateException("Closed compute texture for " + texture.name());
                        }
                        if (nativeTexture.arrayView() != texture.array()) {
                            throw new IllegalArgumentException(
                                    "Sampled view dimensionality mismatch for " + texture.name()
                                            + ": shader expects array=" + texture.array()
                                            + ", view array=" + nativeTexture.arrayView()
                            );
                        }
                        if (!com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.accepts(texture, nativeImage.texture())) {
                            throw new IllegalArgumentException(
                                    "Texture format mismatch for " + texture.name() + ": shader expects "
                                            + texture.format() + ", resource is " + nativeImage.format()
                            );
                        }
                        imageView = nativeTexture.sampledView().vkImageView();
                        sampler = null;
                        volumeSampler = 0;
                    }
                    case ComputeBindings.VolumeTexture nativeVolume -> {
                        ComputeVolume volume = nativeVolume.volume();
                        if (!texture.volume() || volume.isClosed() || !volume.accepts(texture))
                            throw new IllegalArgumentException("Invalid sampled volume for " + texture.name());
                        imageView = volume.vkView();
                        sampler = nativeVolume.sampler();
                        volumeSampler = texture.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE || sampler != null
                                ? 0 : volume.vkSampler(nativeVolume.tilingAxes());
                    }
                }

                if (texture.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) {
                    final int imageBinding = texture.descriptorBinding();
                    final boolean newImage = writtenImages.add(imageBinding);
                    if (newImage) {
                        final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                        info.sampler(0L);
                        info.imageView(imageView);
                        info.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        final VkWriteDescriptorSet set = writeSet(
                                stack, imageBinding, VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                        );
                        set.pImageInfo(info);
                        putWrite(writes, index, set);
                        sharedImages.put(imageBinding, imageView);
                    } else {
                        requireSameImage(sharedImages, imageBinding, imageView, texture.name());
                    }
                    return index + (newImage ? 1 : 0);
                }

                if (sampler == null && volumeSampler == 0) {
                    throw new IllegalArgumentException("Missing sampler for " + texture.name());
                }
                final long samplerHandle = sampler == null ? volumeSampler : ((VulkanGpuSampler) sampler).vkSampler();

                if (texture.form() == ShaderBinding.DescriptorForm.COMBINED_IMAGE_SAMPLER) {
                    final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                    info.sampler(samplerHandle);
                    info.imageView(imageView);
                    info.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    final VkWriteDescriptorSet set = writeSet(
                            stack, texture.descriptorBinding(),
                            VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    );
                    set.pImageInfo(info);
                    putWrite(writes, index, set);
                    return index + 1;
                }

                final int imageBinding = texture.descriptorBinding();
                final boolean newImage = writtenImages.add(imageBinding);
                final int imageWrites;
                if (newImage) {
                    final VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
                    imageInfo.sampler(0L);
                    imageInfo.imageView(imageView);
                    imageInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    final VkWriteDescriptorSet imageSet = writeSet(
                            stack, imageBinding, VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
                    );
                    imageSet.pImageInfo(imageInfo);
                    putWrite(writes, index, imageSet);
                    sharedImages.put(imageBinding, imageView);
                    imageWrites = 1;
                } else {
                    requireSameImage(sharedImages, imageBinding, imageView, texture.name());
                    imageWrites = 0;
                }

                final int samplerBinding = texture.samplerDescriptorBinding();
                if (writtenSamplers.add(samplerBinding)) {
                    sharedSamplers.put(samplerBinding, samplerHandle);
                    final VkDescriptorImageInfo.Buffer samplerInfo = VkDescriptorImageInfo.calloc(1, stack);
                    samplerInfo.sampler(samplerHandle);
                    samplerInfo.imageView(0L);
                    samplerInfo.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    final VkWriteDescriptorSet samplerSet = writeSet(
                            stack, samplerBinding, VK10.VK_DESCRIPTOR_TYPE_SAMPLER
                    );
                    samplerSet.pImageInfo(samplerInfo);
                    putWrite(writes, index + imageWrites, samplerSet);
                    return index + imageWrites + 1;
                }

                final Long previous = sharedSamplers.get(samplerBinding);
                if (previous == null || previous.longValue() != samplerHandle) {
                    throw new IllegalArgumentException(
                            "Shared sampler descriptor " + samplerBinding
                                    + " is bound to different sampler objects for " + texture.name()
                    );
                }
                return index + imageWrites;
            }
            case ShaderBinding.StorageImage image -> {
                long imageView = storageView(bindings, image);
                final VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.sampler(0L);
                info.imageView(imageView);
                info.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                final VkWriteDescriptorSet set = writeSet(
                        stack, image.descriptorBinding(), VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                );
                set.pImageInfo(info);
                putWrite(writes, index, set);
                return index + 1;
            }
            case ShaderBinding.StorageBuffer storage -> {
                final ComputeBindings.Storage value = require(bindings.storage(storage.name()), storage.name());
                if (value.access() != storage.access()) {
                    throw new IllegalArgumentException(
                            "Storage buffer access mismatch for " + storage.name() + ": shader expects "
                                    + storage.access() + ", resource declares " + value.access()
                    );
                }
                final GpuBufferSlice slice = value.slice();
                checkSlice(slice, storage.name());
                if ((slice.buffer().usage() & ComputeUsage.STORAGE_BUFFER) == 0) {
                    throw new IllegalArgumentException(
                            "Buffer lacks the compute storage marker for " + storage.name()
                    );
                }
                final VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack);
                info.buffer(((VulkanGpuBuffer) slice.buffer()).vkBuffer());
                info.offset(slice.offset());
                info.range(slice.length());
                final VkWriteDescriptorSet set = writeSet(
                        stack, storage.descriptorBinding(), VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                );
                set.pBufferInfo(info);
                putWrite(writes, index, set);
                return index + 1;
            }
        }
    }

    private static long storageView(ComputeBindings bindings, ShaderBinding.StorageImage image) {
        if (image.volume()) {
            var value = require(bindings.volumeImage(image.name()), image.name());
            var volume = value.volume();
            if (volume.isClosed() || !image.accepts(volume.format()) || value.access() != image.access())
                throw new IllegalArgumentException("Invalid storage volume for " + image.name());
            return volume.vkStorageView();
        }
        var cube = bindings.cubeImage(image.name());
        if (cube != null) {
            if (!image.array() || !cube.target().writable() || !image.accepts(cube.target().format()) || cube.access() != image.access())
                throw new IllegalArgumentException("Invalid storage cube array for " + image.name());
            return cube.target().vkStorageView();
        }
        var value = require(bindings.image(image.name()), image.name());
        var texture = value.texture();
        if (texture.isClosed()) throw new IllegalStateException("Closed storage image for " + image.name());
        if (!image.accepts(texture.format()))
            throw new IllegalArgumentException("Storage image format mismatch for " + image.name() + ": shader expects "
                    + image.format() + ", resource is " + texture.format());
        if (value.access() != image.access())
            throw new IllegalArgumentException("Storage image access mismatch for " + image.name() + ": shader expects "
                    + image.access() + ", resource declares " + value.access());
        if (texture.isArray() != image.array())
            throw new IllegalArgumentException("Storage image dimensionality mismatch for " + image.name()
                    + ": shader expects array=" + image.array() + ", resource array=" + texture.isArray());
        return texture.vkStorageView(value.mipLevel(), value.baseLayer(), value.layerCount());
    }

    private static VkWriteDescriptorSet writeSet(
            final MemoryStack stack,
            final int binding,
            final int descriptorType
    ) {
        final VkWriteDescriptorSet set = VkWriteDescriptorSet.calloc(stack).sType$Default();
        set.dstBinding(binding);
        set.dstArrayElement(0);
        set.descriptorCount(1);
        set.descriptorType(descriptorType);
        return set;
    }

    private static void putWrite(
            final VkWriteDescriptorSet.Buffer writes,
            final int index,
            final VkWriteDescriptorSet set
    ) {
        writes.position(index);
        writes.put(set);
        writes.position(0);
    }

    private static void checkSlice(final GpuBufferSlice slice, final String name) {
        if (slice.length() <= 0L) {
            throw new IllegalArgumentException("Empty buffer slice for " + name);
        }
        if (slice.buffer().isClosed()) {
            throw new IllegalStateException("Closed buffer for " + name);
        }
    }

    private static <T> T require(final T value, final String name) {
        if (value == null) {
            throw new IllegalArgumentException("Missing compute binding " + name);
        }
        return value;
    }

    private static void requireSameImage(
            final Map<Integer, Long> sharedImages,
            final int binding,
            final long imageView,
            final String name
    ) {
        final Long previous = sharedImages.get(binding);
        if (previous == null || previous.longValue() != imageView) {
            throw new IllegalArgumentException(
                    "Shared image descriptor " + binding + " is bound to different images for " + name
            );
        }
    }

    private static void verifyCapabilities(final VulkanDevice vk, final ShaderProgram program) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(vk.vkDevice().getPhysicalDevice(), features);

            if (program.capabilities().nativeFp16()) {
                throw new UnsupportedOperationException(
                        "Native FP16 permutation is not wired: this runtime does not enable Vulkan shaderFloat16; "
                                + "use the distinct FP32 LDS permutation"
                );
            }
            if (program.capabilities().storageImageExtendedFormats()
                    && !features.shaderStorageImageExtendedFormats()) {
                throw new UnsupportedOperationException(
                        "Shader requires shaderStorageImageExtendedFormats, but the enabled Vulkan device does not have it"
                );
            }
            if (program.capabilities().storageImageWriteWithoutFormat()
                    && !features.shaderStorageImageWriteWithoutFormat()) {
                throw new UnsupportedOperationException(
                        "Shader requires shaderStorageImageWriteWithoutFormat, but the Vulkan device does not support it"
                );
            }
            if (program.capabilities().waveOperations()) {
                final VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                final VkPhysicalDeviceVulkan11Properties vk11 = VkPhysicalDeviceVulkan11Properties.calloc(stack).sType$Default();
                properties.pNext(vk11);
                VK12.vkGetPhysicalDeviceProperties2(vk.vkDevice().getPhysicalDevice(), properties);
                final int required = VK11.VK_SUBGROUP_FEATURE_BASIC_BIT
                        | VK11.VK_SUBGROUP_FEATURE_VOTE_BIT
                        | VK11.VK_SUBGROUP_FEATURE_ARITHMETIC_BIT
                        | VK11.VK_SUBGROUP_FEATURE_BALLOT_BIT
                        | VK11.VK_SUBGROUP_FEATURE_SHUFFLE_BIT
                        | VK11.VK_SUBGROUP_FEATURE_SHUFFLE_RELATIVE_BIT
                        | VK11.VK_SUBGROUP_FEATURE_QUAD_BIT;
                if ((vk11.subgroupSupportedStages() & VK10.VK_SHADER_STAGE_COMPUTE_BIT) == 0
                        || (vk11.subgroupSupportedOperations() & required) != required) {
                    throw new UnsupportedOperationException(
                            "Shader requires the full compute subgroup operation set"
                    );
                }
            }
        }
    }

    private static long createDescriptorSetLayout(final VulkanDevice vk, final ShaderProgram program) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final List<ShaderBinding> bindings = program.bindings();
            final int entryCount = descriptorEntryCount(bindings);
            final VkDescriptorSetLayoutBinding.Buffer nativeBindings = entryCount == 0
                    ? null
                    : VkDescriptorSetLayoutBinding.calloc(entryCount, stack);
            final Set<Integer> added = new HashSet<>();
            int entry = 0;
            for (ShaderBinding binding : bindings) {
                switch (binding) {
                    case ShaderBinding.UniformBuffer uniform -> {
                        entry = putLayoutBindingUnique(
                                nativeBindings, entry, uniform.descriptorBinding(),
                                VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, stack, added
                        );
                    }
                    case ShaderBinding.SampledTexture texture -> {
                        switch (texture.form()) {
                            case COMBINED_IMAGE_SAMPLER -> entry = putLayoutBindingUnique(
                                    nativeBindings, entry, texture.descriptorBinding(),
                                    VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, stack, added
                            );
                            case SAMPLED_IMAGE -> entry = putLayoutBindingUnique(
                                    nativeBindings, entry, texture.descriptorBinding(),
                                    VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, stack, added
                            );
                            case SEPARATE_IMAGE_AND_SAMPLER -> {
                                entry = putLayoutBindingUnique(
                                        nativeBindings, entry, texture.descriptorBinding(),
                                        VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, stack, added
                                );
                                entry = putLayoutBindingUnique(
                                        nativeBindings, entry, texture.samplerDescriptorBinding(),
                                        VK10.VK_DESCRIPTOR_TYPE_SAMPLER, stack, added
                                );
                            }
                        }
                    }
                    case ShaderBinding.StorageImage image -> {
                        entry = putLayoutBindingUnique(
                                nativeBindings, entry, image.descriptorBinding(),
                                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, stack, added
                        );
                    }
                    case ShaderBinding.StorageBuffer storage -> {
                        entry = putLayoutBindingUnique(
                                nativeBindings, entry, storage.descriptorBinding(),
                                VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, stack, added
                        );
                    }
                }
            }

            final VkDescriptorSetLayoutCreateInfo info =
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().flags(1);
            if (nativeBindings != null) {
                nativeBindings.position(0);
                info.pBindings(nativeBindings);
            }
            final LongBuffer handle = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                    vk,
                    VK12.vkCreateDescriptorSetLayout(vk.vkDevice(), info, null, handle),
                    "Failed to create compute descriptor set layout"
            );
            return handle.get(0);
        }
    }

    private static long createPipelineLayout(final VulkanDevice vk, final long descriptorSetLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            info.pSetLayouts(stack.longs(descriptorSetLayout));
            final LongBuffer handle = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                    vk,
                    VK12.vkCreatePipelineLayout(vk.vkDevice(), info, null, handle),
                    "Failed to create compute pipeline layout"
            );
            return handle.get(0);
        }
    }

    private static long createShaderModule(final VulkanDevice vk, final ShaderProgram program) {
        final byte[] spirv = program.vulkanSpirvBytes();
        final ByteBuffer code = MemoryUtil.memAlloc(spirv.length);
        try {
            code.put(spirv).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
                info.pCode(code);
                final LongBuffer handle = stack.callocLong(1);
                VulkanUtils.crashIfFailure(
                        vk,
                        VK12.vkCreateShaderModule(vk.vkDevice(), info, null, handle),
                        "Failed to create compute shader module " + program.name()
                );
                return handle.get(0);
            }
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static long createPipeline(
            final VulkanDevice vk,
            final ShaderProgram program,
            final long pipelineLayout,
            final long shaderModule
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPipelineShaderStageCreateInfo stage =
                    VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default();
            stage.stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            stage.module(shaderModule);
            stage.pName(stack.UTF8(program.vulkanEntryPoint()));

            final VkComputePipelineCreateInfo.Buffer infos = VkComputePipelineCreateInfo.calloc(1, stack);
            final VkComputePipelineCreateInfo info = infos.get(0);
            info.sType$Default();
            info.stage(stage);
            info.layout(pipelineLayout);
            final LongBuffer handle = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                    vk,
                    VK12.vkCreateComputePipelines(vk.vkDevice(), 0L, infos, null, handle),
                    "Failed to create compute pipeline " + program.name()
            );
            return handle.get(0);
        }
    }

    private static int putLayoutBindingUnique(
            final VkDescriptorSetLayoutBinding.Buffer nativeBindings,
            final int index,
            final int binding,
            final int descriptorType,
            final MemoryStack stack,
            final Set<Integer> added
    ) {
        if (!added.add(binding)) {
            return index;
        }
        return putLayoutBinding(nativeBindings, index, binding, descriptorType, stack);
    }

    private static int putLayoutBinding(
            final VkDescriptorSetLayoutBinding.Buffer nativeBindings,
            final int index,
            final int binding,
            final int descriptorType,
            final MemoryStack stack
    ) {
        final VkDescriptorSetLayoutBinding nativeBinding = VkDescriptorSetLayoutBinding.calloc(stack);
        nativeBinding.binding(binding);
        nativeBinding.descriptorType(descriptorType);
        nativeBinding.descriptorCount(1);
        nativeBinding.stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        nativeBindings.position(index);
        nativeBindings.put(nativeBinding);
        return index + 1;
    }

    private static int descriptorEntryCount(final List<ShaderBinding> bindings) {
        final Set<Integer> entries = new HashSet<>();
        for (ShaderBinding binding : bindings) {
            entries.add(binding.descriptorBinding());
            if (binding instanceof ShaderBinding.SampledTexture texture
                    && texture.form() == ShaderBinding.DescriptorForm.SEPARATE_IMAGE_AND_SAMPLER) {
                entries.add(texture.samplerDescriptorBinding());
            }
        }
        return entries.size();
    }

    private static void destroy(
            final VulkanDevice vk,
            final long pipeline,
            final long pipelineLayout,
            final long descriptorSetLayout,
            final long shaderModule
    ) {
        if (pipeline != 0L) {
            VK12.vkDestroyPipeline(vk.vkDevice(), pipeline, null);
        }
        if (pipelineLayout != 0L) {
            VK12.vkDestroyPipelineLayout(vk.vkDevice(), pipelineLayout, null);
        }
        if (descriptorSetLayout != 0L) {
            VK12.vkDestroyDescriptorSetLayout(vk.vkDevice(), descriptorSetLayout, null);
        }
        if (shaderModule != 0L) {
            VK12.vkDestroyShaderModule(vk.vkDevice(), shaderModule, null);
        }
    }
}
