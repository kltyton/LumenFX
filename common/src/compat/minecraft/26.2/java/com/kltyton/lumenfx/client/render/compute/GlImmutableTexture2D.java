package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL42C;

/** Compute-owned immutable 2D storage; required for real glTextureView mip subresources. */
final class GlImmutableTexture2D extends GlTexture {
    private GlImmutableTexture2D(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int mips,
            final int id,
            final FrameBufferCache frameBuffers,
            final boolean renderAttachment
    ) {
        super(
                ComputeUsage.STORAGE_IMAGE
                        | GpuTexture.USAGE_TEXTURE_BINDING
                        | GpuTexture.USAGE_COPY_SRC
                        | GpuTexture.USAGE_COPY_DST
                        | (renderAttachment ? GpuTexture.USAGE_RENDER_ATTACHMENT : 0),
                label,
                format,
                width,
                height,
                1,
                mips,
                id,
                frameBuffers
        );
    }

    static GlImmutableTexture2D create(
            final GlDevice device,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int mips,
            final boolean renderAttachment
    ) {
        final int internal = GlConst.toGlInternalId(format);
        final int external = GlConst.toGlExternalId(format);
        final int type = GlConst.toGlType(format);
        if (internal == 0 || external == 0 || type == 0) {
            throw new IllegalArgumentException("Format is not representable as a GL 2D texture: " + format);
        }

        final int previousBinding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        final int id = GlStateManager._genTexture();
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, mips - 1);
            GL42C.glTexStorage2D(GL11C.GL_TEXTURE_2D, mips, internal, width, height);
        } catch (RuntimeException error) {
            GlStateManager._deleteTexture(id);
            throw error;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousBinding);
        }

        return new GlImmutableTexture2D(label, format, width, height, mips, id, device.frameBufferCache(), renderAttachment);
    }
}
