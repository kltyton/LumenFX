package com.kltyton.lumenfx.client.resource.texture.storage;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

/** Updates one physical VT pool in the actual source storage, including BC tile payloads. */
public final class NativeTileAtlas implements AutoCloseable {
    private final GpuDevice device;
    private final NativeTextureStorage storage;
    private final GpuTexture image;
    private final GpuTextureView view;

    public NativeTileAtlas(String label, int size, NativeTextureStorage storage, boolean linear) {
        device = RenderSystem.getDevice();
        this.storage = storage;
        image = switch (device.backend) {
            case GlDevice gl -> createGl(gl, label, size, storage);
            case VulkanDevice vk -> createVulkan(vk, label, size, storage, linear);
            default -> throw new IllegalStateException("Unsupported virtual texture device " + device.backend.getClass());
        };
        try {
            view = NativeTextureUpload.createView(device, image);
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
    }

    public GpuTextureView view() { return view; }

    public void write(int x, int y, int size, ByteBuffer pixels) {
        RenderSystem.assertOnRenderThread();
        if (x < 0 || y < 0 || size <= 0 || x > image.getWidth(0) - size || y > image.getHeight(0) - size
                || pixels.remaining() != storage.format().byteSize(size, size)
                || storage.format().compressed() && ((x | y | size) & 3) != 0)
            throw new IllegalArgumentException("Physical virtual texture tile does not fit the atlas");
        if (image instanceof SourceGlTexture gl) {
            try (var state = new GlUploadState(gl.glId())) {
                GlStateManager.clearGlErrors();
                if (storage.format().compressed())
                    GL13C.glCompressedTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, x, y, size, size, storage.glFormat(), pixels);
                else GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, x, y, size, size,
                        storage.format().glPixelFormat(), storage.format().glPixelType(), pixels);
                requireGlSuccess("upload");
            }
        } else {
            var vk = (SourceVulkanTexture) image;
            var encoder = ((VulkanDevice) device.backend).createCommandEncoder();
            try (var stack = MemoryStack.stackPush()) {
                GpuBufferSlice staging = encoder.transientMemory().uploadStaging(pixels, 1L, 16);
                var commands = encoder.textureInitCommandBuffer();
                var copy = VkBufferImageCopy.calloc(1, stack);
                copy.bufferOffset(staging.offset());
                copy.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copy.imageOffset().set(x, y, 0);
                copy.imageExtent().set(size, size, 1);
                VulkanCommandEncoder.memoryBarrier(commands, stack);
                VK12.vkCmdCopyBufferToImage(commands, ((VulkanGpuBuffer) staging.buffer()).vkBuffer(),
                        vk.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, copy);
                VulkanCommandEncoder.memoryBarrier(commands, stack);
            }
        }
    }

    @Override public void close() {
        RenderSystem.queueFencedTask(() -> {
            try { view.close(); } finally { image.close(); }
        });
    }

    private static SourceGlTexture createGl(GlDevice device, String label, int size, NativeTextureStorage storage) {
        if (!storage.format().supportedGl(GL.getCapabilities()))
            throw new IllegalStateException("Virtual texture format is unavailable on this GL device: " + storage.format());
        int id = GlStateManager._genTexture();
        try (var state = new GlUploadState(id)) {
            GlStateManager.clearGlErrors();
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, storage.glFormat(), size, size, 0,
                    storage.format().compressed() ? GL11C.GL_RGBA : storage.format().glPixelFormat(),
                    storage.format().compressed() ? GL11C.GL_UNSIGNED_BYTE : storage.format().glPixelType(), 0L);
            requireGlSuccess("allocation");
            return new SourceGlTexture(label, size, size, 1, 1, id, false, device.frameBufferCache(), storage);
        } catch (RuntimeException | Error failure) {
            GlStateManager._deleteTexture(id);
            throw failure;
        }
    }

    private static void requireGlSuccess(String operation) {
        int error = GL11C.glGetError();
        if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("GL virtual texture " + operation + " failed: " + error);
    }

    private static SourceVulkanTexture createVulkan(VulkanDevice device, String label, int size,
                                                    NativeTextureStorage storage, boolean linear) {
        try (var stack = MemoryStack.stackPush()) {
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            var properties = VkFormatProperties.calloc(stack);
            var physicalDevice = device.vkDevice().getPhysicalDevice();
            VK12.vkGetPhysicalDeviceFeatures(physicalDevice, features);
            VK12.vkGetPhysicalDeviceFormatProperties(physicalDevice, storage.vkFormat(), properties);
            int required = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if (linear) required |= VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if (storage.format().compressed() && !features.textureCompressionBC()
                    || (properties.optimalTilingFeatures() & required) != required)
                throw new IllegalStateException("Virtual texture format is unavailable on this Vulkan device: " + storage);
        }
        return SourceVulkanTexture.create(device, label, size, size, 1, 1, false, storage);
    }

    private static final class GlUploadState implements AutoCloseable {
        private static final int[] PARAMETERS = {
                GL11C.GL_UNPACK_ALIGNMENT, GL12C.GL_UNPACK_ROW_LENGTH, GL12C.GL_UNPACK_SKIP_ROWS,
                GL12C.GL_UNPACK_SKIP_PIXELS, GL11C.GL_UNPACK_SWAP_BYTES
        };
        private final int binding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        private final int unpackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int[] unpack = new int[PARAMETERS.length];

        GlUploadState(int texture) {
            GlStateManager._bindTexture(texture);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int index = 0; index < PARAMETERS.length; index++) {
                unpack[index] = GL11C.glGetInteger(PARAMETERS[index]);
                GL11C.glPixelStorei(PARAMETERS[index], index == 0 ? 1 : 0);
            }
        }

        @Override public void close() {
            for (int index = 0; index < PARAMETERS.length; index++) GL11C.glPixelStorei(PARAMETERS[index], unpack[index]);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            GlStateManager._bindTexture(binding);
        }
    }
}
