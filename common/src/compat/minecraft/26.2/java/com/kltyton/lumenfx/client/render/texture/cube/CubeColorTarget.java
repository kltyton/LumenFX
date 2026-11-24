package com.kltyton.lumenfx.client.render.texture.cube;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.util.Optional;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** A color cubemap with a full sampled view and explicit per-face initialization. */
public final class CubeColorTarget implements AutoCloseable {
    private final GpuTexture texture;
    private final GpuTextureView sampled;
    private final @Nullable VulkanDevice vulkan;
    private final int glStorage;
    private final long vkStorage;
    private boolean closed;

    public static int maximumSize() {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        int maximum = device.getDeviceInfo().limits().maxTextureSize();
        if (device.backend instanceof GlDevice)
            return Math.min(maximum, GL11C.glGetInteger(org.lwjgl.opengl.GL13C.GL_MAX_CUBE_MAP_TEXTURE_SIZE));
        if (device.backend instanceof VulkanDevice vk) {
            try (var stack = MemoryStack.stackPush()) {
                var properties = VkPhysicalDeviceProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceProperties(vk.vkDevice().getPhysicalDevice(), properties);
                return Math.min(maximum, properties.limits().maxImageDimensionCube());
            }
        }
        throw new IllegalStateException("Unsupported cube texture backend");
    }

    public CubeColorTarget(String label, GpuFormat format, int size, Vector4fc clear) {
        this(label, format, size, 1, false, false, clear);
    }

    public CubeColorTarget(String label, GpuFormat format, int size, int mips, boolean writable, boolean srgb, Vector4fc clear) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        vulkan = device.backend instanceof VulkanDevice vk ? vk : null;
        if (srgb && format != GpuFormat.RGBA8_UNORM) throw new IllegalArgumentException("sRGB cube views require RGBA8_UNORM");
        if (device.backend instanceof GlDevice gl) texture = GlCubeRenderTexture.create(gl, label, format, size, mips, writable);
        else {
            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_CUBEMAP_COMPATIBLE
                    | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | (writable ? ComputeUsage.STORAGE_IMAGE : 0);
            texture = srgb ? ComputeColorViews.withSrgbAllocation(() -> device.createTexture(label, usage, format, size, size, 6, mips))
                    : device.createTexture(label, usage, format, size, size, 6, mips);
        }
        GpuTextureView view = null;
        int glAlias = 0;
        long vkAlias = 0;
        try {
            view = srgb ? ComputeColorViews.cubeView(texture, mips) : device.createTextureView(texture);
            if (writable) {
                if (vulkan != null) vkAlias = createVulkanStorage(vulkan, (VulkanGpuTexture)texture, format);
                else if (device.backend instanceof GlDevice) glAlias = createGlStorage((GlTexture)texture, format);
                else throw new IllegalStateException("Cube storage has no device implementation");
            }
            var encoder = device.createCommandEncoder();
            var color = new Vector4f(clear);
            if (srgb) color.set(SourceColorMath.srgb8(clear.x()) / 255f, SourceColorMath.srgb8(clear.y()) / 255f,
                    SourceColorMath.srgb8(clear.z()) / 255f, clear.w());
            for (int mip = 0; mip < mips; mip++) {
                int extent = Math.max(1, size >> mip);
                for (int layer = 0; layer < 6; layer++) try (var face = CubeFaceViews.create(device, texture, layer, mip);
                        var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> label)
                                .withColorAttachment(face, Optional.of(color))
                                .withRenderArea(new RenderPass.RenderArea(0, 0, extent, extent)))) { }
            }
        } catch (RuntimeException | Error failure) {
            try { releaseStorage(glAlias, vkAlias); }
            finally {
                try { if (view != null) view.close(); }
                finally { texture.close(); }
            }
            throw failure;
        }
        sampled = view;
        glStorage = glAlias;
        vkStorage = vkAlias;
    }

    public GpuTextureView view() { return sampled; }
    public GpuTexture texture() { return texture; }
    public boolean writable() { return !closed && (glStorage != 0 || vkStorage != 0); }
    public GpuFormat format() { return texture.getFormat(); }
    public int glStorageTexture() {
        if (closed || glStorage == 0) throw new IllegalStateException("Cube storage GL array view is unavailable");
        return glStorage;
    }
    public int glStorageFormat() { return GlConst.toGlInternalId(format()); }
    public long vkStorageView() {
        if (closed || vkStorage == 0) throw new IllegalStateException("Cube storage Vulkan array view is unavailable");
        return vkStorage;
    }

    private static int createGlStorage(GlTexture texture, GpuFormat format) {
        int internal = GlConst.toGlInternalId(format);
        if (GL43C.glGetInternalformati(GL30C.GL_TEXTURE_2D_ARRAY, internal, GL43C.GL_SHADER_IMAGE_STORE) == 0)
            throw new IllegalArgumentException("Cube format does not support array image stores: " + format);
        int alias = GlStateManager._genTexture();
        GlStateManager.clearGlErrors();
        try {
            GL43C.glTextureView(alias, GL30C.GL_TEXTURE_2D_ARRAY, texture.glId(), internal, 0, 1, 0, 6);
            int error = GlStateManager._getError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("Cube storage view failed with GL error " + error);
            return alias;
        } catch (RuntimeException | Error failure) {
            GlStateManager._deleteTexture(alias);
            throw failure;
        }
    }

    private static long createVulkanStorage(VulkanDevice device, VulkanGpuTexture texture, GpuFormat format) {
        try (var stack = MemoryStack.stackPush()) {
            var usage = VkImageViewUsageCreateInfo.calloc(stack).sType$Default().usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT);
            var info = VkImageViewCreateInfo.calloc(stack).sType$Default().pNext(usage.address()).image(texture.vkImage())
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D_ARRAY).format(VulkanConst.toVk(format));
            info.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(6);
            var result = stack.callocLong(1);
            VulkanUtils.crashIfFailure(device, VK12.vkCreateImageView(device.vkDevice(), info, null, result),
                    "Failed to create cube storage array view");
            return result.get(0);
        }
    }

    private void releaseStorage(int gl, long vk) {
        if (gl != 0) RenderSystem.queueFencedTask(() -> GlStateManager._deleteTexture(gl));
        var device = vulkan;
        if (vk != 0 && device != null)
            device.createCommandEncoder().queueForDestroy(() -> VK12.vkDestroyImageView(device.vkDevice(), vk, null));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { releaseStorage(glStorage, vkStorage); }
        finally {
            try { sampled.close(); }
            finally { texture.close(); }
        }
    }
}
