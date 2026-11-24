package com.kltyton.lumenfx.client.render.texture.cube;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import java.util.Objects;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageCopy;

/** Copies the actual six depth layers using the host device and command stream. */
public final class CubeDepthCopy {
    private CubeDepthCopy() { }
    static void copy(CubeDepthTarget source, CubeDepthTarget destination) {
        RenderSystem.assertOnRenderThread();
        GpuTexture input = source.texture(), output = destination.texture();
        if (input.isClosed() || output.isClosed()) throw new IllegalStateException("Cannot copy a closed cube-depth image");
        if (source.size() != destination.size()) throw new IllegalArgumentException("Cube-depth copy requires equal source and destination dimensions");
        if ((input.usage() & GpuTexture.USAGE_COPY_SRC) == 0 || (output.usage() & GpuTexture.USAGE_COPY_DST) == 0)
            throw new IllegalArgumentException("Cube-depth image is missing its required transfer usage");
        if (source == destination) return;
        var device = RenderSystem.getDevice();
        if (device.backend instanceof com.mojang.blaze3d.opengl.GlDevice
                && input instanceof GlTexture glInput && output instanceof GlTexture glOutput) {
            int barriers = GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
            GL42C.glMemoryBarrier(barriers);
            GL43C.glCopyImageSubData(glInput.glId(), GL13C.GL_TEXTURE_CUBE_MAP, 0, 0, 0, 0,
                    glOutput.glId(), GL13C.GL_TEXTURE_CUBE_MAP, 0, 0, 0, 0, source.size(), source.size(), 6);
            GL42C.glMemoryBarrier(barriers);
        } else if (device.backend instanceof com.mojang.blaze3d.vulkan.VulkanDevice vk
                && input instanceof VulkanGpuTexture vkInput && output instanceof VulkanGpuTexture vkOutput) {
            var command = vk.createCommandEncoder().textureInitCommandBuffer();
            try (var stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(command, stack);
                var region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(6);
                region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(6);
                region.srcOffset().set(0, 0, 0); region.dstOffset().set(0, 0, 0);
                region.extent().set(source.size(), source.size(), 1);
                VK12.vkCmdCopyImage(command, vkInput.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL,
                        vkOutput.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, region);
                VulkanCommandEncoder.memoryBarrier(command, stack);
            }
        } else throw new IllegalStateException("Cube-depth images do not belong to the active graphics backend");
    }

    /** Copies all six cube layers into the caller's six independent atlas squares in one backend batch. */
    public static void copyToAtlas(CubeDepthTarget source, GpuTexture destination, int[] x, int[] y) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(source);
        Objects.requireNonNull(destination);
        if (x.length != 6 || y.length != 6) throw new IllegalArgumentException("Cube-depth atlas copies require six face rectangles");
        GpuTexture input = source.texture();
        if (input.isClosed() || destination.isClosed()) throw new IllegalStateException("Cannot copy a closed depth image into an atlas");
        if (input.getFormat() != GpuFormat.D32_FLOAT || destination.getFormat() != GpuFormat.D32_FLOAT
                || destination.getDepthOrLayers() != 1)
            throw new IllegalArgumentException("Cube-depth atlas copies require D32 cube layers and a single-layer D32 atlas");
        if ((input.usage() & GpuTexture.USAGE_COPY_SRC) == 0 || (destination.usage() & GpuTexture.USAGE_COPY_DST) == 0)
            throw new IllegalArgumentException("Cube-depth atlas images are missing their required transfer usage");
        int size = source.size();
        int width = destination.getWidth(0), height = destination.getHeight(0);
        for (int face = 0; face < 6; face++) {
            if (x[face] < 0 || y[face] < 0 || (long)x[face] + size > width || (long)y[face] + size > height)
                throw new IllegalArgumentException("Cube-depth face rectangle lies outside the destination atlas");
        }
        var device = RenderSystem.getDevice();
        if (device.backend instanceof com.mojang.blaze3d.opengl.GlDevice
                && input instanceof GlTexture glInput && destination instanceof GlTexture glOutput) {
            int barriers = GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT;
            GL42C.glMemoryBarrier(barriers);
            for (int face = 0; face < 6; face++)
                GL43C.glCopyImageSubData(glInput.glId(), GL13C.GL_TEXTURE_CUBE_MAP, 0, 0, 0, face,
                        glOutput.glId(), GL13C.GL_TEXTURE_2D, 0, x[face], y[face], 0, size, size, 1);
            GL42C.glMemoryBarrier(barriers);
        } else if (device.backend instanceof com.mojang.blaze3d.vulkan.VulkanDevice vk
                && input instanceof VulkanGpuTexture vkInput && destination instanceof VulkanGpuTexture vkOutput) {
            var command = vk.createCommandEncoder().textureInitCommandBuffer();
            try (var stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(command, stack);
                var regions = VkImageCopy.calloc(6, stack);
                for (int face = 0; face < 6; face++) {
                    var region = regions.get(face);
                    region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(face).layerCount(1);
                    region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                    region.srcOffset().set(0, 0, 0); region.dstOffset().set(x[face], y[face], 0);
                    region.extent().set(size, size, 1);
                }
                VK12.vkCmdCopyImage(command, vkInput.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL,
                        vkOutput.vkImage(), VK12.VK_IMAGE_LAYOUT_GENERAL, regions);
                VulkanCommandEncoder.memoryBarrier(command, stack);
            }
        } else throw new IllegalStateException("Cube-depth images do not belong to the active graphics backend");
    }
}
