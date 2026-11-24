package com.kltyton.lumenfx.client.render.texture.cube;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL42C;

/** Immutable cube allocation shares the existing host texture view counting and framebuffer cache. */
final class GlCubeRenderTexture extends GlTexture {
    private GlCubeRenderTexture(String label, GpuFormat format, int size, int mips, int id, FrameBufferCache cache, boolean writable) {
        super(GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_CUBEMAP_COMPATIBLE
                        | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | (writable ? ComputeUsage.STORAGE_IMAGE : 0),
                label, format, size, size, 6, mips, id, cache);
    }

    static GlCubeRenderTexture create(GlDevice device, String label, GpuFormat format, int size) {
        return create(device, label, format, size, 1, false);
    }

    static GlCubeRenderTexture create(GlDevice device, String label, GpuFormat format, int size, int mips, boolean writable) {
        int maximum = Math.min(device.getDeviceInfo().limits().maxTextureSizeForFormat(format),
                GL11C.glGetInteger(GL13C.GL_MAX_CUBE_MAP_TEXTURE_SIZE));
        if (size <= 0 || size > maximum) throw new IllegalArgumentException("Cube texture size " + size + " exceeds valid device range 1.." + maximum);
        if (mips < 1 || mips > 32 - Integer.numberOfLeadingZeros(size)) throw new IllegalArgumentException("Invalid cube mip count");
        GlStateManager.clearGlErrors();
        int previous = GL11C.glGetInteger(GL13C.GL_TEXTURE_BINDING_CUBE_MAP);
        int id = GlStateManager._genTexture();
        try {
            GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, id);
            GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL13C.GL_TEXTURE_CUBE_MAP, GL12C.GL_TEXTURE_MAX_LEVEL, mips - 1);
            GL42C.glTexStorage2D(GL13C.GL_TEXTURE_CUBE_MAP, mips, GlConst.toGlInternalId(format), size, size);
            int error = GlStateManager._getError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("OpenGL cube allocation failed for " + label + ": " + error);
        } catch (RuntimeException | Error failure) {
            GlStateManager._deleteTexture(id); throw failure;
        } finally { GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, previous); }
        return new GlCubeRenderTexture(label, format, size, mips, id, device.frameBufferCache(), writable);
    }
}
