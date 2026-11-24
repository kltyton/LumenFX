package com.kltyton.lumenfx.runtime.gl;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Captures and restores OpenGL graphics pipeline state around direct LumenFX draw calls
 * so Minecraft, Iris/Oculus, and Sodium/Embeddium state is never disturbed.
 */
public final class GlStateGuard implements AutoCloseable {
    private final int prevProgram;
    private final int prevVao;
    private final int prevArrayBuffer;
    private final int prevElementBuffer;
    private final int prevActiveTexture;
    private final int prevTex2d;
    private final int prevSampler0;
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

    public GlStateGuard() {
        this.prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        this.prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        this.prevArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        this.prevElementBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        this.prevActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        this.prevTex2d = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        this.prevSampler0 = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL33C.glBindSampler(0, 0);
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

    @Override
    public void close() {
        GL20C.glUseProgram(prevProgram);
        GL30C.glBindVertexArray(prevVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, prevArrayBuffer);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, prevElementBuffer);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex2d);
        GL33C.glBindSampler(0, prevSampler0);
        GL13C.glActiveTexture(prevActiveTexture);
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
