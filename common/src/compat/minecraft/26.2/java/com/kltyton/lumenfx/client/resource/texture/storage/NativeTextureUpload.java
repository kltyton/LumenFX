package com.kltyton.lumenfx.client.resource.texture.storage;

import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerViews;
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
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Uploads the original block layout without passing it through RGBA copy contracts. */
public final class NativeTextureUpload {
    private NativeTextureUpload() { }

    public static GpuTexture upload(GpuDevice device, PreparedTexture source) {
        NativeTextureStorage storage = new NativeTextureStorage(
                TextureStorageFormat.from(source.metadata().cookedFormat()), source.metadata().srgb());
        return switch (device.backend) {
            case GlDevice gl -> uploadGl(gl, source, storage);
            case VulkanDevice vk -> uploadVulkan(vk, source, storage);
            default -> throw new IllegalStateException("Unsupported source texture backend " + device.backend.getClass().getName());
        };
    }

    public static GpuTextureView createView(GpuDevice device, GpuTexture texture) {
        return texture instanceof SourceVulkanTexture vk
                ? vk.createView((VulkanDevice) device.backend) : device.createTextureView(texture);
    }

    public static GpuTextureView createRasterArrayView(GpuDevice device, ComputeTexture array) {
        if (array.texture() instanceof SourceVulkanTexture source)
            return NativeTextureCreation.create(source.sampledStorage(), () -> TextureLayerViews.createArray(device, array));
        return TextureLayerViews.createArray(device, array);
    }

    public static ComputeTexture uploadArray(GpuDevice device, PreparedTexture source) {
        if (!source.array()) throw new IllegalArgumentException("A source array layout is required");
        GpuTexture texture = upload(device, source);
        try {
            if (texture instanceof SourceGlTexture gl)
                return ComputeTexture.sourceArray(gl, 0, gl.glId(), GL30C.GL_TEXTURE_2D_ARRAY, gl.sampledStorage().glFormat(),
                        () -> RenderSystem.queueFencedTask(gl::close));
            var vk = (SourceVulkanTexture) texture;
            var backend = (VulkanDevice) device.backend;
            long view = vk.createArrayView(backend);
            try {
                return ComputeTexture.sourceArray(vk, view, 0, 0, 0, () -> {
                    backend.createCommandEncoder().queueForDestroy(() -> VK12.vkDestroyImageView(backend.vkDevice(), view, null));
                    vk.close();
                });
            } catch (RuntimeException | Error failure) {
                backend.createCommandEncoder().queueForDestroy(() -> VK12.vkDestroyImageView(backend.vkDevice(), view, null));
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    private static GpuTexture uploadGl(GlDevice device, PreparedTexture source, NativeTextureStorage storage) {
        if (!storage.format().supportedGl(GL.getCapabilities()))
            throw new IllegalStateException("Source texture storage is not supported by this GL device: " + storage.format());
        GlStateManager.clearGlErrors();
        boolean array = source.array();
        boolean cube = source.cube();
        boolean cubeArray = source.cubeArray();
        boolean layeredUpload = array || cubeArray;
        if (cubeArray && !GL.getCapabilities().OpenGL40)
            throw new IllegalStateException("Source texture requires cubemap array sampling: " + source.id());
        int target = cubeArray ? GL40C.GL_TEXTURE_CUBE_MAP_ARRAY
                : array ? GL30C.GL_TEXTURE_2D_ARRAY : cube ? GL13C.GL_TEXTURE_CUBE_MAP : GL11C.GL_TEXTURE_2D;
        if (layeredUpload && source.layers() > GL11C.glGetInteger(GL30C.GL_MAX_ARRAY_TEXTURE_LAYERS))
            throw new IllegalArgumentException("Source texture array exceeds the GL layer limit: " + source.id());
        if ((cube || cubeArray) && source.metadata().width() > GL11C.glGetInteger(GL13C.GL_MAX_CUBE_MAP_TEXTURE_SIZE))
            throw new IllegalArgumentException("Source cubemap exceeds the GL face limit: " + source.id());
        int binding = GL11C.glGetInteger(cubeArray ? GL40C.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY : array ? GL30C.GL_TEXTURE_BINDING_2D_ARRAY
                : cube ? GL13C.GL_TEXTURE_BINDING_CUBE_MAP : GL11C.GL_TEXTURE_BINDING_2D);
        int alignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        int rowLength = GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_PIXELS);
        int swapBytes = GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES);
        int imageHeight = layeredUpload ? GL11C.glGetInteger(GL12C.GL_UNPACK_IMAGE_HEIGHT) : 0;
        int skipImages = layeredUpload ? GL11C.glGetInteger(GL12C.GL_UNPACK_SKIP_IMAGES) : 0;
        int id = GlStateManager._genTexture();
        try {
            if (layeredUpload) {
                GL11C.glBindTexture(target, id);
                GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
                GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
            } else if (cube) GL11C.glBindTexture(target, id);
            else GlStateManager._bindTexture(id);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, 0);
            int levels = source.mips().size();
            GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL, levels - 1);
            for (int level = 0; level < levels; level++) {
                int width = Math.max(1, source.metadata().width() >> level);
                int height = Math.max(1, source.metadata().height() >> level);
                ByteBuffer bytes = source.mips().get(level).duplicate();
                if (cube) {
                    int faceBytes = storage.format().byteSize(width, height);
                    for (int face = 0; face < 6; face++) uploadSurface(GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face,
                            level, storage, width, height, bytes.slice(bytes.position() + face * faceBytes, faceBytes));
                } else if (layeredUpload) {
                    if (storage.format().compressed())
                        GL13C.glCompressedTexImage3D(target, level, storage.glFormat(), width, height, source.layers(), 0, bytes);
                    else GL12C.glTexImage3D(target, level, storage.glFormat(), width, height, source.layers(),
                            0, storage.format().glPixelFormat(), storage.format().glPixelType(), bytes);
                } else uploadSurface(target, level, storage, width, height, bytes);
            }
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR)
                throw new IllegalStateException("Native effect texture upload failed (GL " + error + "): " + source.id());
            return new SourceGlTexture("LumenFX " + source.id(), source.metadata().width(), source.metadata().height(),
                    source.layers(), levels, id, cube, cubeArray, device.frameBufferCache(), storage);
        } catch (RuntimeException error) {
            GlStateManager._deleteTexture(id);
            throw error;
        } finally {
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, swapBytes);
            if (layeredUpload) {
                GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
                GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, skipImages);
                GL11C.glBindTexture(target, binding);
            } else if (cube) GL11C.glBindTexture(target, binding);
            else GlStateManager._bindTexture(binding);
        }
    }

    private static void uploadSurface(int target, int level, NativeTextureStorage storage, int width, int height, ByteBuffer bytes) {
        if (storage.format().compressed()) GL13C.glCompressedTexImage2D(target, level, storage.glFormat(), width, height, 0, bytes);
        else GL11C.glTexImage2D(target, level, storage.glFormat(), width, height, 0,
                storage.format().glPixelFormat(), storage.format().glPixelType(), bytes);
    }

    private static GpuTexture uploadVulkan(VulkanDevice device, PreparedTexture source, NativeTextureStorage storage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (source.array() || source.cube()) {
                var limits = VkPhysicalDeviceProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), limits);
                if ((source.array() || source.cubeArray()) && source.layers() > limits.limits().maxImageArrayLayers())
                    throw new IllegalArgumentException("Source texture array exceeds the Vulkan layer limit: " + source.id());
                if ((source.cube() || source.cubeArray()) && source.metadata().width() > limits.limits().maxImageDimensionCube())
                    throw new IllegalArgumentException("Source cubemap exceeds the Vulkan face limit: " + source.id());
            }
            VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
            VK12.vkGetPhysicalDeviceFeatures(device.vkDevice().getPhysicalDevice(), features);
            if (storage.format().compressed() && !features.textureCompressionBC())
                throw new IllegalStateException("Source texture requires Vulkan textureCompressionBC: " + source.id());
            if (source.cubeArray() && !features.imageCubeArray())
                throw new IllegalStateException("Source texture requires Vulkan imageCubeArray: " + source.id());
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), storage.vkFormat(), properties);
            int required = VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if (source.metadata().minMag() != com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode.POINT
                    || source.metadata().mip() != com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode.POINT)
                required |= VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((properties.optimalTilingFeatures() & required) != required)
                throw new IllegalStateException("Vulkan source texture format lacks sample/filter/upload support: " + storage);
        }
        SourceVulkanTexture texture = SourceVulkanTexture.create(device, "LumenFX " + source.id(),
                source.metadata().width(), source.metadata().height(), source.layers(), source.mips().size(),
                source.cube(), source.cubeArray(), storage);
        try {
            VulkanCommandEncoder encoder = device.createCommandEncoder();
            VkCommandBuffer commands = encoder.textureInitCommandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (int level = 0; level < source.mips().size(); level++) {
                    GpuBufferSlice staging = encoder.transientMemory().uploadStaging(source.mips().get(level).duplicate(), 1L, 16);
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferOffset(staging.offset()).bufferRowLength(0).bufferImageHeight(0);
                    region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(source.layers());
                    region.imageExtent().set(texture.getWidth(level), texture.getHeight(level), 1);
                    VK12.vkCmdCopyBufferToImage(commands, ((VulkanGpuBuffer) staging.buffer()).vkBuffer(),
                            texture.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, region);
                }
                VulkanCommandEncoder.memoryBarrier(commands, stack);
            }
            return texture;
        } catch (RuntimeException error) {
            texture.close();
            throw error;
        }
    }
}
