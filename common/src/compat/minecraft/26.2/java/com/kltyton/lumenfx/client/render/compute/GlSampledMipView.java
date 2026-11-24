package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTextureView;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

/** A host view has its own shader-visible LOD zero, even when it shares the host GL texture name. */
final class GlSampledMipView {
    private static final int[] VIEW_PARAMETERS = {
            GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_TEXTURE_WRAP_T,
            GL12C.GL_TEXTURE_WRAP_R, GL14C.GL_TEXTURE_COMPARE_MODE, GL14C.GL_TEXTURE_COMPARE_FUNC,
            GL33C.GL_TEXTURE_SWIZZLE_R, GL33C.GL_TEXTURE_SWIZZLE_G, GL33C.GL_TEXTURE_SWIZZLE_B, GL33C.GL_TEXTURE_SWIZZLE_A
    };

    private GlSampledMipView() { }

    static void bind(GlComputeState state, GlTextureView view, int target) {
        int source = view.glId();
        int baseMip = view.fboMipLevel();
        int mipCount = view.mipLevels();
        GL11C.glBindTexture(target, source);
        if (baseMip == 0 && mipCount == view.texture().getMipLevels()) {
            selectFullRange(state, target, source, mipCount);
            return;
        }

        int levelTarget = target == GL13C.GL_TEXTURE_CUBE_MAP ? GL13C.GL_TEXTURE_CUBE_MAP_POSITIVE_X : target;
        int format = GL11C.glGetTexLevelParameteri(levelTarget, baseMip, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
        boolean immutable = GL11C.glGetTexParameteri(target, GL42C.GL_TEXTURE_IMMUTABLE_FORMAT) != 0;
        int layers = switch (target) {
            case GL13C.GL_TEXTURE_CUBE_MAP -> 6;
            case GL30C.GL_TEXTURE_2D_ARRAY, GL40C.GL_TEXTURE_CUBE_MAP_ARRAY, GL12C.GL_TEXTURE_3D ->
                    GL11C.glGetTexLevelParameteri(target, baseMip, GL12C.GL_TEXTURE_DEPTH);
            default -> 1;
        };
        int[] parameters = new int[VIEW_PARAMETERS.length];
        for (int index = 0; index < parameters.length; index++) {
            parameters[index] = GL11C.glGetTexParameteri(target, VIEW_PARAMETERS[index]);
        }
        GlStateManager.clearGlErrors();
        int alias = GL11C.glGenTextures();
        try {
            if (immutable) {
                GL43C.glTextureView(alias, target, source, format, baseMip, mipCount, 0,
                        target == GL12C.GL_TEXTURE_3D ? 1 : layers);
                GL11C.glBindTexture(target, alias);
                setRange(target, mipCount);
            } else {
                // Vanilla GL textures have mutable storage. Copy only the mip window exposed to this dispatch.
                GL11C.glBindTexture(target, alias);
                setRange(target, mipCount);
                allocateCopy(view, source, alias, target, format, baseMip, layers);
            }
            for (int index = 0; index < parameters.length; index++) {
                GL11C.glTexParameteri(target, VIEW_PARAMETERS[index], parameters[index]);
            }
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR)
                throw new IllegalStateException("Could not bind sampled mip " + baseMip + " of GL texture " + source
                        + " (OpenGL error " + error + ")");
            state.releaseTextureAfterDispatch(alias);
        } catch (RuntimeException | Error failure) {
            GL11C.glDeleteTextures(alias);
            throw failure;
        }
    }

    private static void selectFullRange(GlComputeState state, int target, int texture, int mipCount) {
        int base = GL11C.glGetTexParameteri(target, GL12C.GL_TEXTURE_BASE_LEVEL);
        int max = GL11C.glGetTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL);
        if (base == 0 && max == mipCount - 1) return;
        state.recordMipRange(target, texture, base, max);
        setRange(target, mipCount);
    }

    private static void setRange(int target, int mipCount) {
        GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
        GL11C.glTexParameteri(target, GL12C.GL_TEXTURE_MAX_LEVEL, mipCount - 1);
    }

    private static void allocateCopy(GlTextureView view, int source, int alias, int target, int format, int baseMip, int layers) {
        int width = view.getWidth(0);
        int height = view.getHeight(0);
        int mipCount = view.mipLevels();
        if (target == GL11C.GL_TEXTURE_2D || target == GL13C.GL_TEXTURE_CUBE_MAP)
            GL42C.glTexStorage2D(target, mipCount, format, width, height);
        else
            GL42C.glTexStorage3D(target, mipCount, format, width, height, layers);
        GL42C.glMemoryBarrier(GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT);
        for (int mip = 0; mip < mipCount; mip++) {
            int depth = target == GL12C.GL_TEXTURE_3D ? Math.max(1, layers >> mip) : layers;
            GL43C.glCopyImageSubData(source, target, baseMip + mip, 0, 0, 0,
                    alias, target, mip, 0, 0, 0, view.getWidth(mip), view.getHeight(mip), depth);
        }
    }
}
