package com.kltyton.lumenfx.client.render.compute.format;

import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.util.function.Supplier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL43C;

/** A linear UAV and an sRGB SRV share one mutable-format RGBA8 allocation. */
public final class ComputeColorViews {
    private static final ThreadLocal<Boolean> SRGB_ALLOCATION = new ThreadLocal<>();
    private static final ThreadLocal<Integer> SRGB_VIEW_USAGE = new ThreadLocal<>();
    private ComputeColorViews() { }

    public static <T> T withSrgbAllocation(Supplier<T> create) {
        Boolean previous = SRGB_ALLOCATION.get();
        SRGB_ALLOCATION.set(true);
        try { return create.get(); }
        finally { if (previous == null) SRGB_ALLOCATION.remove(); else SRGB_ALLOCATION.set(previous); }
    }

    public static boolean mutableSrgbAllocation() { return Boolean.TRUE.equals(SRGB_ALLOCATION.get()); }
    public static int srgbViewUsage() { Integer usage = SRGB_VIEW_USAGE.get(); return usage == null ? 0 : usage; }
    public static boolean isSrgb(GpuTextureView view) { return view instanceof GlSrgbView || view instanceof VulkanSrgbView; }

    public static GpuTextureView glView(GlDevice device, GlTexture texture, int mips) {
        return new GlSrgbView(device, texture, 0, mips);
    }

    public static GpuTextureView vulkanView(VulkanDevice device, VulkanGpuTexture texture, int mips) {
        return vulkanView(device, texture, 0, mips);
    }

    public static GpuTextureView cubeView(com.mojang.blaze3d.textures.GpuTexture texture, int mips) {
        var device = RenderSystem.getDevice();
        if (device.backend instanceof GlDevice gl) return new GlSrgbView(gl, (GlTexture)texture, 0, mips, true);
        if (device.backend instanceof VulkanDevice vk) return vulkanView(vk, (VulkanGpuTexture)texture, mips);
        throw new IllegalStateException("Unsupported storage cube color view backend");
    }

    private static GpuTextureView vulkanView(VulkanDevice device, VulkanGpuTexture texture, int baseMip, int mips) {
        Integer previous = SRGB_VIEW_USAGE.get();
        int usage = org.lwjgl.vulkan.VK12.VK_IMAGE_USAGE_SAMPLED_BIT;
        if ((texture.usage() & com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT) != 0)
            usage |= org.lwjgl.vulkan.VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        SRGB_VIEW_USAGE.set(usage);
        try {
            return NativeTextureCreation.colorTarget(new NativeTextureStorage(TextureStorageFormat.RGBA8, true),
                    () -> new VulkanSrgbView(device, texture, baseMip, mips));
        } finally { if (previous == null) SRGB_VIEW_USAGE.remove(); else SRGB_VIEW_USAGE.set(previous); }
    }

    public static GpuTextureView colorView(com.mojang.blaze3d.textures.GpuTexture texture, int baseMip, int mips, boolean srgb) {
        var device = RenderSystem.getDevice();
        if (!srgb) return device.createTextureView(texture, baseMip, mips);
        if (device.backend instanceof GlDevice gl) return new GlSrgbView(gl, (GlTexture)texture, baseMip, mips);
        if (device.backend instanceof VulkanDevice vk)
            return vulkanView(vk, (VulkanGpuTexture)texture, baseMip, mips);
        throw new IllegalStateException("Unsupported storage image color view backend");
    }

    private static final class VulkanSrgbView extends VulkanGpuTextureView {
        VulkanSrgbView(VulkanDevice device, VulkanGpuTexture texture, int baseMip, int mips) { super(device, texture, baseMip, mips); }
    }

    private static final class GlSrgbView extends GlTextureView {
        private int id;
        GlSrgbView(GlDevice device, GlTexture texture, int baseMip, int mips) {
            this(device, texture, baseMip, mips, false);
        }
        GlSrgbView(GlDevice device, GlTexture texture, int baseMip, int mips, boolean cube) {
            super(texture, baseMip, mips, device.frameBufferCache());
            id = GlStateManager._genTexture();
            try {
                GlStateManager.clearGlErrors();
                GL43C.glTextureView(id, cube ? GL13C.GL_TEXTURE_CUBE_MAP : GL11C.GL_TEXTURE_2D,
                        texture.glId(), GL21C.GL_SRGB8_ALPHA8, baseMip, mips, 0, cube ? 6 : 1);
                int error = GlStateManager._getError();
                if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("sRGB storage view creation failed: " + error);
            } catch (RuntimeException | Error error) {
                GlStateManager._deleteTexture(id); id = 0; super.close(); throw error;
            }
        }
        @Override public int glId() { return id; }
        @Override public int fboMipLevel() { return 0; }
        @Override public void close() {
            if (isClosed()) return;
            super.close();
            int released = id; id = 0;
            if (released != 0) RenderSystem.queueFencedTask(() -> GlStateManager._deleteTexture(released));
        }
    }
}
