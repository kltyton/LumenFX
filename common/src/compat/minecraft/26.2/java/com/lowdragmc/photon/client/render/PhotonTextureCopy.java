/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.lowdragmc.photon.Photon;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import javax.annotation.Nullable;

/**
 * An owned copy of a texture Photon reads while drawing over it — the scene behind the FX, the frame a distortion
 * bends, the depth a light pass reconstructs from. Same size and format as its source, kept between frames and
 * reallocated when either changes. A source with {@code COPY_SRC} is copied ({@code copyTextureToTexture} needs the
 * formats to match on Vulkan); a colour source without it is drawn over with a sampling pass; a depth source without
 * it cannot be copied at all. Render thread, outside any pass.
 */
public final class PhotonTextureCopy implements AutoCloseable {

    private final String label;
    private final int usage;
    @Nullable
    private GpuTexture texture;
    @Nullable
    private GpuTextureView view;
    private int width, height;
    @Nullable
    private GpuFormat format;
    private boolean warnedUncopyable;
    private int depthReadFbo, depthDrawFbo;

    public PhotonTextureCopy(String label) {
        this(label, GpuTexture.USAGE_RENDER_ATTACHMENT);
    }

    public PhotonTextureCopy(String label, int additionalUsage) {
        this.label = label;
        this.usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | additionalUsage;
    }

    /** Sized and formatted as {@code source}, its content left as it is. */
    private GpuTextureView ensureLike(GpuTexture source) {
        var w = source.getWidth(0);
        var h = source.getHeight(0);
        if (texture == null || w != width || h != height || source.getFormat() != format) {
            close();
            var device = RenderSystem.getDevice();
            var next = device.createTexture(() -> label, usage, source.getFormat(), w, h, 1, 1);
            try {
                view = device.createTextureView(next);
            } catch (RuntimeException | Error failure) {
                next.close();
                throw failure;
            }
            texture = next;
            width = w;
            height = h;
            format = source.getFormat();
        }
        return view;
    }

    /** A copy of {@code source}; null when it can be neither copied nor sampled (warned once). */
    @Nullable
    public GpuTextureView copyOf(GpuTextureView source) {
        return copyOf(RenderSystem.getDevice().createCommandEncoder(), source);
    }

    /** Uses the encoder that owns this stage's texture operations. */
    @Nullable
    public GpuTextureView copyOf(CommandEncoder encoder, GpuTextureView source) {
        RenderSystem.assertOnRenderThread();
        if (source.baseMipLevel() != 0 || source.texture().getDepthOrLayers() != 1) {
            throw new IllegalArgumentException(label + " requires a base-level 2D attachment");
        }
        var from = source.texture();
        if ((from.usage() & GpuTexture.USAGE_COPY_SRC) != 0) {
            var to = ensureLike(from);
            encoder.copyTextureToTexture(from, to.texture(), 0, 0, 0, 0, 0, width, height);
            return to;
        }
        if (from.getFormat().hasDepthAspect() && from instanceof GlTexture fromGl) {
            var to = ensureLike(from);
            if (to.texture() instanceof GlTexture toGl) {
                blitGlDepth(fromGl.glId(), toGl.glId(), width, height);
                return to;
            }
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 && !from.getFormat().hasDepthAspect()
                && (from.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            var to = ensureLike(from);
            PhotonFullscreenPass.copy(label, source, to, null);
            return to;
        }
        // view() must not hand out a texture this never wrote
        close();
        if (!warnedUncopyable) {
            warnedUncopyable = true;
            Photon.LOGGER.warn("{}: the target being drawn into ({}) can neither be copied nor sampled — "
                    + "effects reading it get nothing this frame", label, from.getLabel());
        }
        return null;
    }

    /** The copy as it stands, or null before the first. */
    @Nullable
    public GpuTextureView view() {
        return view;
    }

    private void blitGlDepth(int sourceId, int destId, int w, int h) {
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean srgb = GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
        try {
            if (depthReadFbo == 0) depthReadFbo = GL30C.glGenFramebuffers();
            if (depthDrawFbo == 0) depthDrawFbo = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, depthReadFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, sourceId, 0);
            GL11C.glReadBuffer(GL11C.GL_NONE);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, depthDrawFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, destId, 0);
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            GL30C.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, 0, 0);
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            if (srgb) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
            else GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
        }
    }

    @Override
    public void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        if (depthReadFbo != 0) { GL30C.glDeleteFramebuffers(depthReadFbo); depthReadFbo = 0; }
        if (depthDrawFbo != 0) { GL30C.glDeleteFramebuffers(depthDrawFbo); depthDrawFbo = 0; }
        view = null;
        texture = null;
        format = null;
    }
}
