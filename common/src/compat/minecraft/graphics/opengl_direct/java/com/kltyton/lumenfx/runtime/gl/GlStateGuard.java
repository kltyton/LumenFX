package com.kltyton.lumenfx.runtime.gl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Captures and restores OpenGL graphics pipeline state (including framebuffers, viewport, active material texture units,
 * blend/depth/cull/color mask) around direct LumenFX draw calls so Minecraft, Iris/Oculus, and Sodium/Embeddium
 * state is never disturbed.
 */
public final class GlStateGuard implements AutoCloseable {
    static final int MATERIAL_TEXTURE_START = 4;

    private final int prevDrawFbo;
    private final int prevReadFbo;
    private final int[] prevViewport = new int[4];
    private final int prevProgram;
    private final int prevVao;
    private final int prevArrayBuffer;
    private final int prevElementBuffer;
    private final int prevActiveTexture;
    private final int[] prevTex2d;
    private final int[] prevSamplers;
    private final int prevUniformBuffer;
    private final int prevEffectFrameBuffer;
    private final long prevEffectFrameStart;
    private final long prevEffectFrameSize;
    private final boolean prevBlend;
    private final int prevBlendSrcRgb;
    private final int prevBlendDstRgb;
    private final int prevBlendSrcAlpha;
    private final int prevBlendDstAlpha;
    private final int prevBlendEqRgb;
    private final int prevBlendEqAlpha;
    private final boolean prevDepthTest;
    private final boolean prevDepthMask;
    private final int prevDepthFunc;
    private final boolean prevCullFace;
    private final boolean[] prevColorMask = new boolean[4];

    public GlStateGuard(int textureUnits) {
        int maxUnits = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        if (textureUnits > maxUnits) {
            throw new IllegalArgumentException("LumenFX material requires " + textureUnits + " texture units; available: " + maxUnits);
        }
        this.prevTex2d = new int[textureUnits];
        this.prevSamplers = new int[textureUnits];
        this.prevUniformBuffer = GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING);
        this.prevEffectFrameBuffer = GL30C.glGetIntegeri(GL31C.GL_UNIFORM_BUFFER_BINDING, 0);
        this.prevEffectFrameStart = org.lwjgl.opengl.GL32C.glGetInteger64i(GL31C.GL_UNIFORM_BUFFER_START, 0);
        this.prevEffectFrameSize = org.lwjgl.opengl.GL32C.glGetInteger64i(GL31C.GL_UNIFORM_BUFFER_SIZE, 0);
        this.prevDrawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        this.prevReadFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer vp = MemoryUtil.memAllocInt(4);
        try {
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
            for (int i = 0; i < 4; i++) prevViewport[i] = vp.get(i);
        } finally {
            MemoryUtil.memFree(vp);
        }
        this.prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        this.prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        this.prevArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        this.prevElementBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        this.prevActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        for (int u = 0; u < prevTex2d.length; u++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + u);
            this.prevTex2d[u] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            this.prevSamplers[u] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            GL33C.glBindSampler(u, 0);
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        this.prevBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        this.prevBlendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        this.prevBlendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        this.prevBlendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        this.prevBlendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        this.prevBlendEqRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        this.prevBlendEqAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        this.prevDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        this.prevDepthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        this.prevDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        this.prevCullFace = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        ByteBuffer mask = MemoryUtil.memAlloc(4);
        try {
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) prevColorMask[i] = mask.get(i) != 0;
        } finally {
            MemoryUtil.memFree(mask);
        }
    }

    public int drawFramebuffer() { return prevDrawFbo; }
    public int viewportWidth() { return prevViewport[2]; }
    public int viewportHeight() { return prevViewport[3]; }

    @Override
    public void close() {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevReadFbo);
        GL11C.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        GL20C.glUseProgram(prevProgram);
        GL30C.glBindVertexArray(prevVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, prevArrayBuffer);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, prevElementBuffer);
        for (int u = 0; u < prevTex2d.length; u++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + u);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex2d[u]);
            GL33C.glBindSampler(u, prevSamplers[u]);
        }
        GL13C.glActiveTexture(prevActiveTexture);
        if (prevEffectFrameBuffer != 0 && prevEffectFrameSize > 0) {
            GL30C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, 0, prevEffectFrameBuffer,
                    prevEffectFrameStart, prevEffectFrameSize);
        } else {
            GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, 0, prevEffectFrameBuffer);
        }
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, prevUniformBuffer);
        if (prevBlend) GL11C.glEnable(GL11C.GL_BLEND); else GL11C.glDisable(GL11C.GL_BLEND);
        GL14C.glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
        GL20C.glBlendEquationSeparate(prevBlendEqRgb, prevBlendEqAlpha);
        if (prevDepthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(prevDepthMask);
        GL11C.glDepthFunc(prevDepthFunc);
        if (prevCullFace) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glColorMask(prevColorMask[0], prevColorMask[1], prevColorMask[2], prevColorMask[3]);
    }
}
