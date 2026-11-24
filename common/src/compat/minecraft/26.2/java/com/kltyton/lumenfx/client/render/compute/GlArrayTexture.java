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
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;

/** OpenGL array textures for compute resources and render attachments. */
final class GlArrayTexture extends GlTexture {
    static final int TARGET = GL30C.GL_TEXTURE_2D_ARRAY;

    private GlArrayTexture(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int layers,
            final int mips,
            final boolean renderAttachment,
            final boolean storageWritable,
            final int id,
            final FrameBufferCache frameBuffers
    ) {
        super(
                GpuTexture.USAGE_TEXTURE_BINDING
                        | (storageWritable ? ComputeUsage.STORAGE_IMAGE : 0)
                        | GpuTexture.USAGE_COPY_SRC
                        | GpuTexture.USAGE_COPY_DST
                        | (renderAttachment ? GpuTexture.USAGE_RENDER_ATTACHMENT : 0),
                label,
                format,
                width,
                height,
                layers,
                mips,
                id,
                frameBuffers
        );
    }

    static GlArrayTexture create(
            final GlDevice device,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int layers,
            final int mips,
            final boolean renderAttachment,
            final boolean storageWritable,
            final int maxSize,
            final int maxLayers
    ) {
        if (layers > maxLayers) {
            throw new IllegalArgumentException("Requested " + layers + " array layers, device supports " + maxLayers);
        }
        if (width > maxSize || height > maxSize) {
            throw new IllegalArgumentException("Requested " + width + "x" + height + " texture, device supports " + maxSize);
        }

        final int internal = GlConst.toGlInternalId(format);
        final int external = GlConst.toGlExternalId(format);
        final int type = GlConst.toGlType(format);
        if (internal == 0 || external == 0 || type == 0) {
            throw new IllegalArgumentException("Format is not representable as a GL texture: " + format);
        }

        final int previousBinding = GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);
        final int id = GlStateManager._genTexture();
        try {
            GL11C.glBindTexture(TARGET, id);
            GL11C.glTexParameteri(TARGET, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(TARGET, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(TARGET, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
            GL11C.glTexParameteri(TARGET, GL12C.GL_TEXTURE_MAX_LEVEL, mips - 1);
            // Immutable storage is required for glTextureView subresource views.
            GL42C.glTexStorage3D(TARGET, mips, internal, width, height, layers);
        } catch (RuntimeException error) {
            GlStateManager._deleteTexture(id);
            throw error;
        } finally {
            GL11C.glBindTexture(TARGET, previousBinding);
        }

        return new GlArrayTexture(label, format, width, height, layers, mips, renderAttachment, storageWritable, id, device.frameBufferCache());
    }
}
