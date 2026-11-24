package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import java.nio.LongBuffer;
import java.util.function.LongConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;

/** Vulkan compute runtime. Uses the host device, host command encoder and push descriptors. */
final class VulkanComputeRuntime extends ComputeRuntime {
    private final GpuDevice device;
    private final VulkanDevice vk;
    private final int maxTexture2D;
    private final int maxTextureArrayLayers;
    private final long maxStorageBufferBytes;
    private final long maxTexelBufferElements;
    private final long storageBufferAlignment;
    private final int[] maxWorkGroups = new int[3];
    private final java.util.Map<GpuFormat, Boolean> colorImageStores = new java.util.EnumMap<>(GpuFormat.class);

    VulkanComputeRuntime(final GpuDevice device, final VulkanDevice vk) {
        this.device = device;
        this.vk = vk;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(vk.vkDevice().getPhysicalDevice(), properties);
            this.maxTexture2D = properties.limits().maxImageDimension2D();
            this.maxTextureArrayLayers = properties.limits().maxImageArrayLayers();
            this.maxStorageBufferBytes = Math.min(Integer.toUnsignedLong(properties.limits().maxStorageBufferRange()),
                    device.getDeviceInfo().limits().maxMemoryAllocationSize());
            this.maxTexelBufferElements = Integer.toUnsignedLong(properties.limits().maxTexelBufferElements());
            this.storageBufferAlignment = properties.limits().minStorageBufferOffsetAlignment();
            for (int axis = 0; axis < 3; axis++)
                this.maxWorkGroups[axis] = (int)Math.min(Integer.MAX_VALUE,
                        Integer.toUnsignedLong(properties.limits().maxComputeWorkGroupCount(axis)));
        }
    }

    @Override public long maxStorageBufferBytes() { return maxStorageBufferBytes; }
    @Override public long maxTexelBufferElements() { return maxTexelBufferElements; }
    @Override public long storageBufferAlignment() { return storageBufferAlignment; }
    @Override public int maxWorkGroups(int axis) { return maxWorkGroups[java.util.Objects.checkIndex(axis, 3)]; }

    @Override public boolean supportsColorImageStores(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        if (!com.kltyton.lumenfx.client.render.compute.format.StorageImageFormats.floatingColor(format)) return false;
        return colorImageStores.computeIfAbsent(format, value -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var properties = VkFormatProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceFormatProperties(vk.vkDevice().getPhysicalDevice(), VulkanConst.toVk(value), properties);
                return (properties.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
            }
        });
    }

    @Override public boolean supportsGridArrayFormat(GpuFormat format) {
        RenderSystem.assertOnRenderThread();
        return VulkanComputeVolume.supportsGridFormat(vk, TextureStorageFormat.forRenderTarget(format));
    }

    @Override public boolean supportsSignedImage2D() {
        RenderSystem.assertOnRenderThread();
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(vk.vkDevice().getPhysicalDevice(), VK10.VK_FORMAT_R32_SINT, properties);
            int required = VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_ATOMIC_BIT
                    | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                    | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            return (properties.optimalTilingFeatures() & required) == required;
        }
    }

    @Override protected void clearSignedImage2DInternal(ComputeTexture texture, int value) {
        VkCommandBuffer command = vk.createCommandEncoder().textureInitCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(command, stack);
            var color = VkClearColorValue.calloc(stack).int32(0, value);
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK12.vkCmdClearColorImage(command, ((VulkanGpuTexture)texture.texture()).vkImage(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
            VulkanCommandEncoder.memoryBarrier(command, stack);
        }
    }

    @Override protected void clearColor2DInternal(ComputeTexture texture) {
        clearColorArrayInternal(texture, 1, new float[4]);
    }

    @Override protected void clearColorArrayInternal(ComputeTexture texture, int layers, float[] values) {
        VkCommandBuffer command = vk.createCommandEncoder().textureInitCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(command, stack);
            var color = VkClearColorValue.calloc(stack);
            for (int channel = 0; channel < 4; channel++) color.float32(channel, values[channel]);
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(texture.mips()).baseArrayLayer(0).layerCount(layers);
            VK12.vkCmdClearColorImage(command, ((VulkanGpuTexture)texture.texture()).vkImage(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
            VulkanCommandEncoder.memoryBarrier(command, stack);
        }
    }

    @Override protected void clearBufferInternal(GpuBufferSlice slice, int value) {
        VkCommandBuffer command = vk.createCommandEncoder().textureInitCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(command, stack);
            VK12.vkCmdFillBuffer(command, ((VulkanGpuBuffer)slice.buffer()).vkBuffer(),
                    slice.offset(), slice.length(), value);
            VulkanCommandEncoder.memoryBarrier(command, stack);
        }
    }

    @Override
    protected ComputeTexture createTextureInternal(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final boolean array,
            final int layers,
            final int mips,
            final boolean renderAttachment,
            final boolean storageWritable
    ) {
        verifyTextureArgs(label, format, width, height, array, layers, mips);
        if (storageWritable) verifyStorageFormat(this.vk, format);
        verifyExtents(width, height, layers);

        final int usage = GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_COPY_DST
                | (storageWritable ? ComputeUsage.STORAGE_IMAGE : 0)
                | (renderAttachment ? GpuTexture.USAGE_RENDER_ATTACHMENT : 0);

        final LongConsumer viewDestroyer = handle -> this.vk.createCommandEncoder().queueForDestroy(
                () -> VK12.vkDestroyImageView(this.vk.vkDevice(), handle, null)
        );

        if (!array) {
            final boolean srgb = com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.mutableSrgbAllocation();
            final GpuTexture texture = this.device.createTexture(
                    () -> label,
                    usage,
                    format,
                    width,
                    height,
                    1,
                    mips
            );
            try {
                final GpuTextureView view = this.device.createTextureView(texture);
                final long imageView = ((VulkanGpuTextureView) view).vkImageView();
                final GpuTextureView sampled;
                try {
                    sampled = srgb ? com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.vulkanView(
                            this.vk, (VulkanGpuTexture)texture, mips) : view;
                } catch (RuntimeException | Error error) { view.close(); throw error; }
                return new ComputeTexture(
                        texture,
                        sampled,
                        format,
                        width,
                        height,
                        false,
                        1,
                        mips,
                        imageView,
                        0,
                        0,
                        0,
                        (mipLevel, baseLayer, layerCount, arrayView) -> createSampledView(
                                this.vk,
                                (VulkanGpuTexture) texture,
                                format,
                                false,
                                mipLevel,
                                baseLayer,
                                layerCount,
                                arrayView,
                                srgb
                        ),
                        (mipLevel, baseLayer, layerCount) -> createStorageView(
                                this.vk,
                                (VulkanGpuTexture) texture,
                                format,
                                false,
                                mipLevel,
                                baseLayer,
                                layerCount
                        ),
                        viewDestroyer,
                        null,
                        () -> {
                            if (sampled != view) sampled.close();
                            view.close();
                            texture.close();
                        }
                );
            } catch (RuntimeException error) {
                texture.close();
                throw error;
            }
        }

        final boolean srgb = com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews.mutableSrgbAllocation();
        final GpuTexture texture = this.vk.createTexture(label, usage, format, width, height, layers, mips);
        final long samplingView;
        try {
            samplingView = createArrayView(this.vk, (VulkanGpuTexture) texture, format, mips, layers, srgb);
        } catch (RuntimeException error) {
            texture.close();
            throw error;
        }
        return new ComputeTexture(
                texture,
                null,
                format,
                width,
                height,
                true,
                layers,
                mips,
                samplingView,
                0,
                0,
                0,
                (mipLevel, baseLayer, layerCount, arrayView) -> createSampledView(
                        this.vk,
                        (VulkanGpuTexture) texture,
                        format,
                        true,
                        mipLevel,
                        baseLayer,
                        layerCount,
                        arrayView,
                        srgb
                ),
                (mipLevel, baseLayer, layerCount) -> createStorageView(
                        this.vk,
                        (VulkanGpuTexture) texture,
                        format,
                        true,
                        mipLevel,
                        baseLayer,
                        layerCount
                ),
                viewDestroyer,
                null,
                () -> {
                    viewDestroyer.accept(samplingView);
                    texture.close();
                }
        );
    }

    @Override
    protected ComputeTexture createZeroTextureInternal(
            final String label,
            final GpuFormat format,
            final boolean array
    ) {
        final ComputeTexture texture = createTextureInternal(label, format, 1, 1, array, 1, 1, false);
        try {
            final VkCommandBuffer commandBuffer = this.vk.createCommandEncoder().textureInitCommandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final VkClearColorValue clearColor = VkClearColorValue.calloc(stack);
                clearColor.uint32(0, 0);
                clearColor.uint32(1, 0);
                clearColor.uint32(2, 0);
                clearColor.uint32(3, 0);
                final VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
                range.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
                range.baseMipLevel(0);
                range.levelCount(1);
                range.baseArrayLayer(0);
                range.layerCount(1);
                VK12.vkCmdClearColorImage(
                        commandBuffer,
                        ((VulkanGpuTexture) texture.texture()).vkImage(),
                        VK10.VK_IMAGE_LAYOUT_GENERAL,
                        clearColor,
                        range
                );
                VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
            }
            return texture;
        } catch (RuntimeException error) {
            texture.close();
            throw error;
        }
    }

    @Override
    protected void copyArrayRegionInternal(
            final ComputeTexture source,
            final int sourceLayer,
            final int sourceMip,
            final ComputeTexture destination,
            final int destinationMip,
            final int destinationX,
            final int destinationY,
            final int width,
            final int height,
            final int layers
    ) {
        RenderSystem.assertOnRenderThread();
        final VkCommandBuffer commandBuffer = this.vk.createCommandEncoder().textureInitCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);

            final VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            final VkImageSubresourceLayers sourceSubresource = region.srcSubresource();
            sourceSubresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            sourceSubresource.mipLevel(sourceMip);
            sourceSubresource.baseArrayLayer(sourceLayer);
            sourceSubresource.layerCount(layers);
            final VkImageSubresourceLayers destinationSubresource = region.dstSubresource();
            destinationSubresource.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            destinationSubresource.mipLevel(destinationMip);
            destinationSubresource.baseArrayLayer(0);
            destinationSubresource.layerCount(layers);
            region.srcOffset().set(0, 0, 0);
            region.dstOffset().set(destinationX, destinationY, 0);
            region.extent().set(width, height, 1);

            VK12.vkCmdCopyImage(
                    commandBuffer,
                    ((VulkanGpuTexture) source.texture()).vkImage(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    ((VulkanGpuTexture) destination.texture()).vkImage(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    region
            );
            VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
        }
    }

    @Override
    public ComputeKernel createKernel(final ShaderProgram program) {
        return new VulkanComputeKernel(this.device, this.vk, program);
    }

    @Override
    public int maxTexture2D() {
        return this.maxTexture2D;
    }

    @Override
    public int maxTextureArrayLayers() {
        return this.maxTextureArrayLayers;
    }

    private static void verifyStorageFormat(final VulkanDevice vk, final GpuFormat format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkFormatProperties properties = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(
                    vk.vkDevice().getPhysicalDevice(),
                    VulkanConst.toVk(format),
                    properties
            );
            final int storage = VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if ((properties.optimalTilingFeatures() & storage) == 0) {
                throw new IllegalArgumentException(
                        "Vulkan format lacks storage-image support: " + format
                                + " (" + VulkanConst.toVk(format) + ")"
                );
            }
        }
    }

    private void verifyExtents(final int width, final int height, final int layers) {
        if (width > this.maxTexture2D || height > this.maxTexture2D) {
            throw new IllegalArgumentException(
                    "Compute texture " + width + "x" + height
                            + " exceeds maxImageDimension2D " + this.maxTexture2D
            );
        }
        if (layers > this.maxTextureArrayLayers) {
            throw new IllegalArgumentException(
                    "Compute texture layer count " + layers
                            + " exceeds maxImageArrayLayers " + this.maxTextureArrayLayers
            );
        }
    }

    private static long createArrayView(
            final VulkanDevice vk,
            final VulkanGpuTexture texture,
            final GpuFormat format,
            final int mips,
            final int layers,
            final boolean srgb
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default();
            info.image(texture.vkImage());
            info.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY);
            info.format(srgb ? VK12.VK_FORMAT_R8G8B8A8_SRGB : VulkanConst.toVk(format));
            if (srgb) {
                var usage = VkImageViewUsageCreateInfo.calloc(stack).sType$Default().usage(VK12.VK_IMAGE_USAGE_SAMPLED_BIT);
                info.pNext(usage.address());
            }
            final VkImageSubresourceRange range = info.subresourceRange();
            range.aspectMask(format.hasColorAspect()
                    ? VK10.VK_IMAGE_ASPECT_COLOR_BIT
                    : VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            range.baseMipLevel(0);
            range.levelCount(mips);
            range.baseArrayLayer(0);
            range.layerCount(layers);
            final LongBuffer handle = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                    vk,
                    VK12.vkCreateImageView(vk.vkDevice(), info, null, handle),
                    "Failed to create compute array sampling view"
            );
            return handle.get(0);
        }
    }

    private static ComputeTexture.SampledView createSampledView(
            final VulkanDevice vk,
            final VulkanGpuTexture texture,
            final GpuFormat format,
            final boolean array,
            final int mipLevel,
            final int baseLayer,
            final int layerCount,
            final boolean arrayView
    ) {
        return createSampledView(vk, texture, format, array, mipLevel, baseLayer, layerCount, arrayView, false);
    }

    private static ComputeTexture.SampledView createSampledView(
            VulkanDevice vk, VulkanGpuTexture texture, GpuFormat format, boolean array,
            int mipLevel, int baseLayer, int layerCount, boolean arrayView, boolean srgb
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default();
            info.image(texture.vkImage());
            info.viewType(arrayView ? VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK10.VK_IMAGE_VIEW_TYPE_2D);
            info.format(srgb ? VK12.VK_FORMAT_R8G8B8A8_SRGB : VulkanConst.toVk(format));
            final VkImageSubresourceRange range = info.subresourceRange();
            range.aspectMask(format.hasColorAspect()
                    ? VK10.VK_IMAGE_ASPECT_COLOR_BIT
                    : VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            range.baseMipLevel(mipLevel);
            range.levelCount(1);
            range.baseArrayLayer(array ? baseLayer : 0);
            range.layerCount(arrayView ? layerCount : 1);
            final LongBuffer handle = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                    vk,
                    VK12.vkCreateImageView(vk.vkDevice(), info, null, handle),
                    "Failed to create compute sampled subresource view"
            );
            return new ComputeTexture.SampledView(handle.get(0), 0, 0, true);
        }
    }

    private static long createStorageView(
            final VulkanDevice vk,
            final VulkanGpuTexture texture,
            final GpuFormat format,
            final boolean array,
            final int mipLevel,
            final int baseLayer,
            final int layerCount
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack).sType$Default();
            info.image(texture.vkImage());
            info.viewType(array ? VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK10.VK_IMAGE_VIEW_TYPE_2D);
            info.format(VulkanConst.toVk(format));
            final VkImageSubresourceRange range = info.subresourceRange();
            range.aspectMask(format.hasColorAspect()
                    ? VK10.VK_IMAGE_ASPECT_COLOR_BIT
                    : VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            range.baseMipLevel(mipLevel);
            range.levelCount(1);
            range.baseArrayLayer(array ? baseLayer : 0);
            range.layerCount(array ? layerCount : 1);
            final LongBuffer handle = stack.callocLong(1);
            VulkanUtils.crashIfFailure(
                    vk,
                    VK12.vkCreateImageView(vk.vkDevice(), info, null, handle),
                    "Failed to create compute storage subresource view"
            );
            return handle.get(0);
        }
    }
}
