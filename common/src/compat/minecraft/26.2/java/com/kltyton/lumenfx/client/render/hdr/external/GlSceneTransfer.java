package com.kltyton.lumenfx.client.render.hdr.external;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Converts framebuffer attachment formats without taking ownership of shaderpack textures. */
public final class GlSceneTransfer implements AutoCloseable {
    private int readFramebuffer, drawFramebuffer;

    public void importColor(int source, int width, int height, GpuTexture destination) {
        importColor(source, GL11C.GL_TEXTURE_2D, width, height, destination, width, height);
    }

    public void importColor(int source, int sourceTarget, int sourceWidth, int sourceHeight,
                            GpuTexture destination, int destinationWidth, int destinationHeight) {
        transfer(source, sourceTarget, sourceWidth, sourceHeight,
                ((GlTexture)destination).glId(), GL11C.GL_TEXTURE_2D, destinationWidth, destinationHeight,
                GL11C.GL_COLOR_BUFFER_BIT);
    }

    public void exportColor(GpuTexture source, int destination, int width, int height) {
        exportColor(source, width, height, destination, GL11C.GL_TEXTURE_2D, width, height);
    }

    public void exportColor(GpuTexture source, int sourceWidth, int sourceHeight,
                            int destination, int destinationTarget, int destinationWidth, int destinationHeight) {
        transfer(((GlTexture)source).glId(), GL11C.GL_TEXTURE_2D, sourceWidth, sourceHeight,
                destination, destinationTarget, destinationWidth, destinationHeight,
                GL11C.GL_COLOR_BUFFER_BIT);
    }

    public void transferDepth(GpuTexture source, int sourceWidth, int sourceHeight,
                              GpuTexture destination, int destinationWidth, int destinationHeight) {
        transfer(((GlTexture)source).glId(), GL11C.GL_TEXTURE_2D, sourceWidth, sourceHeight,
                ((GlTexture)destination).glId(), GL11C.GL_TEXTURE_2D, destinationWidth, destinationHeight,
                GL11C.GL_DEPTH_BUFFER_BIT);
    }

    private void transfer(int source, int sourceTarget, int sourceWidth, int sourceHeight,
                          int destination, int destinationTarget, int destinationWidth, int destinationHeight,
                          int mask) {
        RenderSystem.assertOnRenderThread();
        if (source <= 0 || destination <= 0 || source == destination) {
            throw new IllegalArgumentException("Scene transfer requires distinct valid attachments");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0 || destinationWidth <= 0 || destinationHeight <= 0) {
            throw new IllegalArgumentException("Scene transfer requires positive dimensions");
        }
        int attachment = mask == GL11C.GL_DEPTH_BUFFER_BIT ? GL30C.GL_DEPTH_ATTACHMENT : GL30C.GL_COLOR_ATTACHMENT0;
        int filter = mask == GL11C.GL_COLOR_BUFFER_BIT
                && (sourceWidth != destinationWidth || sourceHeight != destinationHeight)
                ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean srgb = GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
        try {
            if (readFramebuffer == 0) readFramebuffer = GL30C.glGenFramebuffers();
            if (drawFramebuffer == 0) drawFramebuffer = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, attachment, sourceTarget, source, 0);
            GL11C.glReadBuffer(attachment == GL30C.GL_DEPTH_ATTACHMENT ? GL11C.GL_NONE : GL30C.GL_COLOR_ATTACHMENT0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachment, destinationTarget, destination, 0);
            GL11C.glDrawBuffer(attachment == GL30C.GL_DEPTH_ATTACHMENT ? GL11C.GL_NONE : GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            GL30C.glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, 0, 0, destinationWidth, destinationHeight,
                    mask, filter);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, attachment, sourceTarget, 0, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachment, destinationTarget, 0, 0);
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            if (srgb) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
            else GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (readFramebuffer != 0) GL30C.glDeleteFramebuffers(readFramebuffer);
        if (drawFramebuffer != 0) GL30C.glDeleteFramebuffers(drawFramebuffer);
        readFramebuffer = drawFramebuffer = 0;
    }
}
