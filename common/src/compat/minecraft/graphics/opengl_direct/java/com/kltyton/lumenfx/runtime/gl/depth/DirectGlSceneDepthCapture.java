package com.kltyton.lumenfx.runtime.gl.depth;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;

/**
 * Captures the active Minecraft / Iris / Oculus framebuffer's depth buffer and optional scene color
 * directly via OpenGL FBO blit / copy so soft particles, volumetric fog, refraction, decals, and
 * deferred lights work seamlessly across Minecraft 1.20.1, 1.21.1, and 26.1.2 without version-specific hooks.
 */
public final class DirectGlSceneDepthCapture {
    private int depthTex;
    private int colorTex;
    private int captureFbo;
    private int width;
    private int height;
    private float nearPlane = 0.05f;
    private float farPlane = 256.0f;

    public int depthTextureId() { return depthTex; }
    public int colorTextureId() { return colorTex; }
    public int width() { return width; }
    public int height() { return height; }
    public float nearPlane() { return nearPlane; }
    public float farPlane() { return farPlane; }

    public void captureFromActiveFbo(int sourceDrawFbo, int vpWidth, int vpHeight,
                                     Matrix4f projection, boolean captureColor) {
        if (vpWidth <= 0 || vpHeight <= 0) return;
        extractNearFar(projection);
        ensureTextures(vpWidth, vpHeight, captureColor);

        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceDrawFbo);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
            GL30C.glBlitFramebuffer(0, 0, vpWidth, vpHeight, 0, 0, vpWidth, vpHeight,
                    GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
            if (captureColor && colorTex != 0) {
                GL30C.glBlitFramebuffer(0, 0, vpWidth, vpHeight, 0, 0, vpWidth, vpHeight,
                        GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);
            }
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    private void ensureTextures(int w, int h, boolean needColor) {
        boolean resized = (w != width || h != height || depthTex == 0 || captureFbo == 0);
        boolean needNewColor = needColor && colorTex == 0;
        if (!resized && !needNewColor) return;

        if (resized) {
            dispose();
            width = w;
            height = h;
            depthTex = GL11C.glGenTextures();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, w, h, 0,
                    GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_INT, (ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

            captureFbo = GL30C.glGenFramebuffers();
            int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL11C.GL_TEXTURE_2D, depthTex, 0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
        if (needColor && colorTex == 0) {
            colorTex = GL11C.glGenTextures();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, colorTex);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, width, height, 0,
                    GL11C.GL_RGBA, GL30C.GL_HALF_FLOAT, (ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

            int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, colorTex, 0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    private void extractNearFar(Matrix4f proj) {
        float m22 = proj.m22();
        float m32 = proj.m32();
        if (Math.abs(m22 - 1.0f) > 1e-5f && Math.abs(m22 + 1.0f) > 1e-5f) {
            float n = m32 / (m22 - 1.0f);
            float f = m32 / (m22 + 1.0f);
            if (Float.isFinite(n) && Float.isFinite(f) && n > 0f && f > n) {
                nearPlane = n;
                farPlane = f;
                return;
            }
        }
        nearPlane = 0.05f;
        farPlane = 256.0f;
    }

    public void dispose() {
        if (captureFbo != 0) {
            GL30C.glDeleteFramebuffers(captureFbo);
            captureFbo = 0;
        }
        if (depthTex != 0) {
            GL11C.glDeleteTextures(depthTex);
            depthTex = 0;
        }
        if (colorTex != 0) {
            GL11C.glDeleteTextures(colorTex);
            colorTex = 0;
        }
        width = 0;
        height = 0;
    }
}
