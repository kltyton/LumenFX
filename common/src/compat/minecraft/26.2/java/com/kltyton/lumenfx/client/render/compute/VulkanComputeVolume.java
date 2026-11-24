package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews;

import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/** Immutable 3D source image on the host Vulkan device and command stream. */
final class VulkanComputeVolume extends ComputeVolume {
    private final VulkanDevice device;
    private final VulkanVolumeGpuTexture hostTexture;
    private final NativeTextureStorage storage;
    private final long image, view, storageView;
    private final boolean transferSource;
    private final GpuSampler[] samplers = new GpuSampler[8];

    static boolean supportsGridFormat(VulkanDevice device, TextureStorageFormat format) {
        try (var stack = MemoryStack.stackPush()) {
            var physical = device.vkDevice().getPhysicalDevice();
            var properties = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(physical, format.vkFormat(false), properties);
            int required = VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                    | VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if ((properties.optimalTilingFeatures() & required) != required) return false;
            return switch (format) {
                case R32F, RGBA8, RGBA16F, RGBA32F -> true;
                default -> {
                    var features = VkPhysicalDeviceFeatures.calloc(stack);
                    VK12.vkGetPhysicalDeviceFeatures(physical, features);
                    yield features.shaderStorageImageExtendedFormats();
                }
            };
        }
    }
    VulkanComputeVolume(VulkanDevice device, String label, Payload source) {
        super(source);
        this.device = device;
        storage = source.storage();
        var target = source.target();
        boolean mutable = target != null && source.storage().srgb();
        int format = mutable ? source.storage().format().vkFormat(false) : source.storage().vkFormat();
        long imageHandle = 0, viewHandle = 0, storageHandle = 0;
        VulkanVolumeGpuTexture texture = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties limits = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), limits);
            int maximum = limits.limits().maxImageDimension3D();
            if (width() > maximum || height() > maximum || depth() > maximum)
                throw new IllegalArgumentException("Volume exceeds Vulkan 3D texture limit: " + label);
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(device.vkDevice().getPhysicalDevice(), features);
            if (source.storage().format().compressed() && !features.textureCompressionBC())
                throw new IllegalStateException("Vulkan source volume requires BC sampling: " + source.storage());
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), format, properties);
            int required = VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if (sampling == null || sampling.xy().minMag() != TextureMetadata.FilterMode.POINT
                    || sampling.xy().mip() != TextureMetadata.FilterMode.POINT)
                required |= VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if (writable()) required |= VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
            if (writable() && format() == GpuFormat.R32_SINT) required |= VK10.VK_FORMAT_FEATURE_STORAGE_IMAGE_ATOMIC_BIT;
            if ((properties.optimalTilingFeatures() & required) != required)
                throw new IllegalStateException("Vulkan volume sampling is unavailable for " + source.storage());
            transferSource = target != null && (properties.optimalTilingFeatures() & VK12.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT) != 0;
            int usage = VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    | (transferSource ? VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0)
                    | (writable() ? VK10.VK_IMAGE_USAGE_STORAGE_BIT : 0);
            int flags = mutable ? VK12.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT | VK12.VK_IMAGE_CREATE_EXTENDED_USAGE_BIT : 0;
            texture = NativeTextureCreation.volume(source.storage(), depth(), usage, flags, format,
                    () -> new VulkanVolumeGpuTexture(device, label, format(), width(), height(), depth(), mipLevels(), transferSource));
            imageHandle = texture.vkImage();
            viewHandle = createView(stack, imageHandle, source.storage().vkFormat(), mutable ? VK10.VK_IMAGE_USAGE_SAMPLED_BIT : 0);
            if (writable()) storageHandle = mutable ? createView(stack, imageHandle, format, VK10.VK_IMAGE_USAGE_STORAGE_BIT) : viewHandle;
            VulkanCommandEncoder encoder = device.createCommandEncoder();
            var commands = encoder.textureInitCommandBuffer();
            if (target != null) {
                var clear = VkClearColorValue.calloc(stack);
                if (format() == GpuFormat.R32_SINT) clear.int32(0, 0);
                else {
                    float[] color = source.targetClear();
                    for (int channel = 0; channel < 4; channel++) clear.float32(channel, color[channel]);
                }
                var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                VK12.vkCmdClearColorImage(commands, imageHandle, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
            }
            for (int mip = 0; mip < source.mips().size(); mip++) {
                GpuBufferSlice staging = encoder.transientMemory().uploadStaging(source.mips().get(mip).duplicate(), 1L, 16);
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.bufferOffset(staging.offset()).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(mip).baseArrayLayer(0).layerCount(1);
                region.imageExtent().set(Math.max(1, width() >> mip), Math.max(1, height() >> mip), Math.max(1, depth() >> mip));
                VK12.vkCmdCopyBufferToImage(commands, ((VulkanGpuBuffer) staging.buffer()).vkBuffer(),
                        imageHandle, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            }
            VulkanCommandEncoder.memoryBarrier(commands, stack);
            hostTexture = texture;
            image = imageHandle;
            view = viewHandle;
            storageView = storageHandle;
        } catch (RuntimeException | Error error) {
            long failedView = viewHandle, failedStorage = storageHandle;
            device.createCommandEncoder().queueForDestroy(() -> destroyViews(device, failedView, failedStorage));
            if (texture != null) texture.close();
            throw error;
        }
    }

    private long createView(MemoryStack stack, long image, int format, int usage) {
        var info = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_3D).format(format);
        if (usage != 0) info.pNext(VkImageViewUsageCreateInfo.calloc(stack).sType$Default().usage(usage).address());
        info.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(mipLevels())
                .baseArrayLayer(0).layerCount(1);
        var output = stack.callocLong(1);
        VulkanUtils.crashIfFailure(device, VK12.vkCreateImageView(device.vkDevice(), info, null, output),
                "Failed to create Niagara 3D image view");
        return output.get(0);
    }

    @Override int glTexture() { throw new IllegalStateException("Vulkan volume has no OpenGL texture"); }
    @Override int glStorageTexture() { throw new IllegalStateException("Vulkan volume has no OpenGL texture"); }
    @Override int glStorageFormat() { throw new IllegalStateException("Vulkan volume has no OpenGL format"); }
    @Override int glSampler(int axes) { throw new IllegalStateException("Vulkan volume has no OpenGL sampler"); }
    @Override GpuTextureView createRasterView() {
        if (isClosed()) throw new IllegalStateException("Volume closed");
        if (writable() && storage.srgb()) return ComputeColorViews.colorView(hostTexture, 0, mipLevels(), true);
        return NativeTextureCreation.create(storage, () -> RenderSystem.getDevice().createTextureView(hostTexture));
    }
    @Override GpuSampler samplerObject(int axes) {
        if (isClosed()) throw new IllegalStateException("Volume closed");
        int index = sampling == null ? java.util.Objects.checkIndex(axes, samplers.length) : 0;
        if (samplers[index] == null) samplers[index] = createSamplerObject(axes);
        return samplers[index];
    }
    @Override void clearSignedInternal(int value) {
        var commands = device.createCommandEncoder().textureInitCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commands, stack);
            var clear = VkClearColorValue.calloc(stack).int32(0, value);
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK12.vkCmdClearColorImage(commands, image, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
            VulkanCommandEncoder.memoryBarrier(commands, stack);
        }
    }
    @Override void clearColorInternal(float[] value) {
        var commands = device.createCommandEncoder().textureInitCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commands, stack);
            var clear = VkClearColorValue.calloc(stack);
            for (int channel = 0; channel < 4; channel++) clear.float32(channel, value[channel]);
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK12.vkCmdClearColorImage(commands, image, VK10.VK_IMAGE_LAYOUT_GENERAL, clear, range);
            VulkanCommandEncoder.memoryBarrier(commands, stack);
        }
    }
    @Override void copyInternal(ComputeVolume destination) {
        if (!transferSource) throw new IllegalStateException("Vulkan volume format has no transfer source support");
        var target = (VulkanComputeVolume)destination;
        var commands = device.createCommandEncoder().textureInitCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commands, stack);
            var copy = VkImageCopy.calloc(1, stack);
            copy.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.extent().set(width(), height(), depth());
            VK12.vkCmdCopyImage(commands, image, VK10.VK_IMAGE_LAYOUT_GENERAL, target.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
            VulkanCommandEncoder.memoryBarrier(commands, stack);
        }
    }
    @Override long vkView() { if (isClosed()) throw new IllegalStateException("Volume closed"); return view; }
    @Override long vkStorageView() {
        if (isClosed() || !writable()) throw new IllegalStateException("Volume is not writable");
        return storageView;
    }
    @Override long vkSampler(int axes) {
        return ((VulkanGpuSampler)samplerObject(axes)).vkSampler();
    }

    @Override void writeCacheTiles(CommandEncoder encoder, NiagaraCacheVolumeFrame frame) {
        clearColor(frame.fallback());
        var commands = device.createCommandEncoder().textureInitCommandBuffer();
        if (frame.pages().isEmpty()) return;
        var pixels = frame.pixelData();
        GpuBufferSlice staging;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(pixels.remaining(), 16, GpuBuffer.USAGE_COPY_SRC)) {
            mapped.data().put(pixels);
            staging = mapped.slice();
        }
        var vkBuffer = (VulkanGpuBuffer)staging.buffer();
        int tile = NiagaraCacheVolumeFrame.TILE_SIZE;
        for (int base = 0; base < frame.pages().size(); base += 256) {
            int count = Math.min(256, frame.pages().size() - base);
            try (var stack = MemoryStack.stackPush()) {
                var copies = VkBufferImageCopy.calloc(count, stack);
                for (int index = 0; index < count; index++) {
                    var page = frame.pages().get(base + index);
                    var copy = copies.get(index);
                    copy.bufferOffset(staging.offset() + (long)page.tile() * frame.tileBytes()).bufferRowLength(tile).bufferImageHeight(tile);
                    copy.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    copy.imageOffset().set(page.x(), page.y(), page.z());
                    copy.imageExtent().set(Math.min(tile, width() - page.x()), Math.min(tile, height() - page.y()), Math.min(tile, depth() - page.z()));
                }
                VK12.vkCmdCopyBufferToImage(commands, vkBuffer.vkBuffer(), image, VK10.VK_IMAGE_LAYOUT_GENERAL, copies);
            }
        }
        try (var stack = MemoryStack.stackPush()) { VulkanCommandEncoder.memoryBarrier(commands, stack); }
    }

    @Override void readCachePixels(CommandEncoder encoder, GpuBufferSlice destination) {
        if (!transferSource) throw new IllegalStateException("Niagara volume format cannot be read back on this Vulkan device");
        var commands = device.createCommandEncoder().textureInitCommandBuffer();
        try (var stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(commands, stack);
            var copy = VkBufferImageCopy.calloc(1, stack);
            copy.bufferOffset(destination.offset());
            copy.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            copy.imageExtent().set(width(), height(), depth());
            VK12.vkCmdCopyImageToBuffer(commands, image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    ((VulkanGpuBuffer)destination.buffer()).vkBuffer(), copy);
            VulkanCommandEncoder.memoryBarrier(commands, stack);
        }
    }

    @Override void releaseNative() {
        GpuSampler[] capturedSamplers = samplers.clone();
        GpuTextureView rasterView = detachRasterView();
        device.createCommandEncoder().queueForDestroy(() -> destroyViews(device, view, storageView));
        for (GpuSampler sampler : capturedSamplers) if (sampler != null) sampler.close();
        if (rasterView != null) rasterView.close();
        hostTexture.close();
    }

    private static void destroyViews(VulkanDevice device, long view, long storageView) {
        if (view != 0) VK12.vkDestroyImageView(device.vkDevice(), view, null);
        if (storageView != 0 && storageView != view) VK12.vkDestroyImageView(device.vkDevice(), storageView, null);
    }
}
