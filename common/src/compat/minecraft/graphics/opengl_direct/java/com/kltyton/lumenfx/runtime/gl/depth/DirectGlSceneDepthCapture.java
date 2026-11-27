package com.kltyton.lumenfx.runtime.gl.depth;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Captures the active Minecraft / Iris / Oculus framebuffer's depth buffer and optional scene color
 * directly via OpenGL FBO blit / MSAA resolve / copy so soft particles, volumetric fog, refraction,
 * decals, and deferred lights work across standard 2D, 2D-array, renderbuffer, and multisampled
 * (MSAA) depth attachments on OpenGL 3.2+ Core Profile.
 */
public final class DirectGlSceneDepthCapture {
    public record DepthAttachmentInfo(int internalFormat, int objectType, int objectName,
                                      int textureTarget, int samples, int textureLevel, int textureLayer) {
        public static final DepthAttachmentInfo DEFAULT = new DepthAttachmentInfo(
                GL14C.GL_DEPTH_COMPONENT24, GL11C.GL_NONE, 0, GL11C.GL_TEXTURE_2D, 0, 0, 0);

        public boolean isMultisampled() {
            return samples > 0
                    || textureTarget == GL32C.GL_TEXTURE_2D_MULTISAMPLE
                    || textureTarget == GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
        }
    }

    private static final String MSAA_RESOLVE_VERT = """
            #version 150 core
            void main() {
                vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String MSAA_RESOLVE_2D_FRAG = """
            #version 150 core
            uniform sampler2DMS uMsaaDepth;
            void main() {
                gl_FragDepth = texelFetch(uMsaaDepth, ivec2(gl_FragCoord.xy), 0).r;
            }
            """;

    private static final String MSAA_RESOLVE_ARRAY_FRAG = """
            #version 150 core
            uniform sampler2DMSArray uMsaaDepth;
            uniform int uLayer;
            void main() {
                gl_FragDepth = texelFetch(uMsaaDepth, ivec3(ivec2(gl_FragCoord.xy), uLayer), 0).r;
            }
            """;

    private int depthTex;
    private int colorTex;
    private int captureFbo;
    private int resolve2dProg;
    private int resolveArrayProg;
    private int resolveVao;
    private int width;
    private int height;
    private int depthInternalFormat;
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
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        DepthAttachmentInfo srcInfo = queryDepthAttachment(sourceDrawFbo);
        ensureTextures(vpWidth, vpHeight, srcInfo.internalFormat(), captureColor);

        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceDrawFbo);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
            if (sourceDrawFbo == 0) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
                GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, vpWidth, vpHeight);
            } else {
                // Drain prior errors so format/MSAA fallbacks react strictly to this blit.
                while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
                GL30C.glBlitFramebuffer(0, 0, vpWidth, vpHeight, 0, 0, vpWidth, vpHeight,
                        GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
                if (GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                    if (srcInfo.objectType() == GL11C.GL_TEXTURE
                            && (srcInfo.textureTarget() == GL32C.GL_TEXTURE_2D_MULTISAMPLE
                            || srcInfo.textureTarget() == GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY)) {
                        resolveMultisampleDepthTexture(srcInfo, vpWidth, vpHeight);
                    } else if (!srcInfo.isMultisampled()) {
                        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
                        GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, vpWidth, vpHeight);
                    }
                    while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
                }
            }
            if (captureColor && colorTex != 0) {
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceDrawFbo);
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
                int filter = srcInfo.isMultisampled() ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
                GL30C.glBlitFramebuffer(0, 0, vpWidth, vpHeight, 0, 0, vpWidth, vpHeight,
                        GL11C.GL_COLOR_BUFFER_BIT, filter);
            }
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    /** Queries the source FBO's depth attachment internal format so depth blits never hit GL_INVALID_OPERATION. */
    public static int queryDepthInternalFormat(int fbo) {
        return queryDepthAttachment(fbo).internalFormat();
    }

    /**
     * Inspects the source FBO's depth attachment target, exact internal format, sample count, mip level,
     * and array layer across {@code GL_TEXTURE_2D}, {@code GL_TEXTURE_2D_MULTISAMPLE},
     * {@code GL_TEXTURE_2D_ARRAY}, {@code GL_TEXTURE_2D_MULTISAMPLE_ARRAY}, {@code GL_TEXTURE_RECTANGLE},
     * cube faces, and {@code GL_RENDERBUFFER}.
     */
    public static DepthAttachmentInfo queryDepthAttachment(int fbo) {
        if (fbo == 0) return DepthAttachmentInfo.DEFAULT;
        int prevRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
            int attachment = GL30C.GL_DEPTH_ATTACHMENT;
            int objType = GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (objType == GL11C.GL_NONE) {
                attachment = GL30C.GL_DEPTH_STENCIL_ATTACHMENT;
                objType = GL30C.glGetFramebufferAttachmentParameteri(
                        GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            }
            if (objType == GL11C.GL_NONE) return DepthAttachmentInfo.DEFAULT;
            int objName = GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (objName == 0) return DepthAttachmentInfo.DEFAULT;

            if (objType == GL30C.GL_RENDERBUFFER) {
                int prevRbo = GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
                try {
                    GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, objName);
                    int fmt = GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_INTERNAL_FORMAT);
                    int samples = GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_SAMPLES);
                    return new DepthAttachmentInfo(normalizeDepthFormat(fmt), objType, objName,
                            GL30C.GL_RENDERBUFFER, Math.max(0, samples), 0, 0);
                } finally {
                    GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, prevRbo);
                }
            }

            if (objType == GL11C.GL_TEXTURE) {
                int level = Math.max(0, GL30C.glGetFramebufferAttachmentParameteri(
                        GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL));
                int layer = Math.max(0, GL30C.glGetFramebufferAttachmentParameteri(
                        GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER));
                int cubeFace = GL30C.glGetFramebufferAttachmentParameteri(
                        GL30C.GL_READ_FRAMEBUFFER, attachment, GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);
                while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }

                if (cubeFace != 0) {
                    int prevCube = GL11C.glGetInteger(GL13C.GL_TEXTURE_BINDING_CUBE_MAP);
                    try {
                        GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, objName);
                        int fmt = GL11C.glGetTexLevelParameteri(cubeFace, level, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
                        return new DepthAttachmentInfo(normalizeDepthFormat(fmt), objType, objName,
                                GL13C.GL_TEXTURE_CUBE_MAP, 0, level, layer);
                    } finally {
                        GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, prevCube);
                    }
                }

                // Direct GL 4.5 target query avoids generating any probe errors on modern drivers.
                int dsaTarget = queryTextureTargetGl45(objName);
                if (dsaTarget != 0) {
                    DepthAttachmentInfo info = inspectTextureOnTarget(dsaTarget, objName, level, layer);
                    if (info != null) return info;
                }

                int[] targets = {
                        GL11C.GL_TEXTURE_2D,
                        GL32C.GL_TEXTURE_2D_MULTISAMPLE,
                        GL30C.GL_TEXTURE_2D_ARRAY,
                        GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY,
                        GL31C.GL_TEXTURE_RECTANGLE
                };
                for (int candidate : targets) {
                    DepthAttachmentInfo info = inspectTextureOnTarget(candidate, objName, level, layer);
                    if (info != null) return info;
                }
            }
        } catch (RuntimeException ignored) {
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevRead);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
        }
        return DepthAttachmentInfo.DEFAULT;
    }

    private static int queryTextureTargetGl45(int texName) {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps != null && caps.OpenGL45) {
                int target = GL45C.glGetTextureParameteri(texName, GL45C.GL_TEXTURE_TARGET);
                if (GL11C.glGetError() == GL11C.GL_NO_ERROR && target != 0) {
                    return target;
                }
            }
        } catch (RuntimeException ignored) {
        }
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
        return 0;
    }

    private static DepthAttachmentInfo inspectTextureOnTarget(int target, int texName, int level, int layer) {
        int bindingEnum = textureBindingEnum(target);
        if (bindingEnum == 0) return null;
        int prevTex = GL11C.glGetInteger(bindingEnum);
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
        try {
            GL11C.glBindTexture(target, texName);
            if (GL11C.glGetError() != GL11C.GL_NO_ERROR) return null;
            int queryLevel = (target == GL32C.GL_TEXTURE_2D_MULTISAMPLE || target == GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY)
                    ? 0 : level;
            int fmt = GL11C.glGetTexLevelParameteri(target, queryLevel, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            if (GL11C.glGetError() != GL11C.GL_NO_ERROR || fmt == 0) return null;
            int samples = 0;
            if (target == GL32C.GL_TEXTURE_2D_MULTISAMPLE || target == GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY) {
                samples = Math.max(1, GL11C.glGetTexLevelParameteri(target, 0, GL32C.GL_TEXTURE_SAMPLES));
            }
            return new DepthAttachmentInfo(normalizeDepthFormat(fmt), GL11C.GL_TEXTURE, texName,
                    target, samples, queryLevel, layer);
        } finally {
            GL11C.glBindTexture(target, prevTex);
            while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
        }
    }

    private static int textureBindingEnum(int target) {
        return switch (target) {
            case GL11C.GL_TEXTURE_2D -> GL11C.GL_TEXTURE_BINDING_2D;
            case GL32C.GL_TEXTURE_2D_MULTISAMPLE -> GL32C.GL_TEXTURE_BINDING_2D_MULTISAMPLE;
            case GL30C.GL_TEXTURE_2D_ARRAY -> GL30C.GL_TEXTURE_BINDING_2D_ARRAY;
            case GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> GL32C.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY;
            case GL31C.GL_TEXTURE_RECTANGLE -> GL31C.GL_TEXTURE_BINDING_RECTANGLE;
            default -> 0;
        };
    }

    private void resolveMultisampleDepthTexture(DepthAttachmentInfo srcInfo, int vpWidth, int vpHeight) {
        boolean isArray = srcInfo.textureTarget() == GL32C.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
        int prog = ensureResolveProgram(isArray);
        if (prog == 0) return;

        int prevProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int bindingEnum = isArray ? GL32C.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY : GL32C.GL_TEXTURE_BINDING_2D_MULTISAMPLE;
        int prevMsTex = GL11C.glGetInteger(bindingEnum);
        boolean prevDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean prevDepthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        int prevDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        boolean prevCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean prevBlend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean prevScissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        int[] prevVp = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, prevVp);

        try {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
            GL11C.glViewport(0, 0, vpWidth, vpHeight);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glColorMask(false, false, false, false);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(true);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);

            GL20C.glUseProgram(prog);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(srcInfo.textureTarget(), srcInfo.objectName());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(prog, "uMsaaDepth"), 0);
            if (isArray) {
                GL20C.glUniform1i(GL20C.glGetUniformLocation(prog, "uLayer"), srcInfo.textureLayer());
            }
            GL30C.glBindVertexArray(resolveVao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        } finally {
            GL30C.glBindVertexArray(prevVao);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(srcInfo.textureTarget(), prevMsTex);
            GL13C.glActiveTexture(prevActiveTex);
            GL20C.glUseProgram(prevProgram);
            GL11C.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3]);
            GL11C.glColorMask(true, true, true, true);
            if (prevDepthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthMask(prevDepthMask);
            GL11C.glDepthFunc(prevDepthFunc);
            if (prevCull) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);
            if (prevBlend) GL11C.glEnable(GL11C.GL_BLEND); else GL11C.glDisable(GL11C.GL_BLEND);
            if (prevScissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST); else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        }
    }

    private int ensureResolveProgram(boolean arrayTarget) {
        if (resolveVao == 0) resolveVao = GL30C.glGenVertexArrays();
        if (arrayTarget) {
            if (resolveArrayProg == 0) resolveArrayProg = linkResolve(MSAA_RESOLVE_VERT, MSAA_RESOLVE_ARRAY_FRAG);
            return resolveArrayProg;
        }
        if (resolve2dProg == 0) resolve2dProg = linkResolve(MSAA_RESOLVE_VERT, MSAA_RESOLVE_2D_FRAG);
        return resolve2dProg;
    }

    private static int linkResolve(String vertSrc, String fragSrc) {
        int vs = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
        GL20C.glShaderSource(vs, vertSrc);
        GL20C.glCompileShader(vs);
        if (GL20C.glGetShaderi(vs, GL20C.GL_COMPILE_STATUS) == 0) {
            GL20C.glDeleteShader(vs);
            return 0;
        }
        int fs = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
        GL20C.glShaderSource(fs, fragSrc);
        GL20C.glCompileShader(fs);
        if (GL20C.glGetShaderi(fs, GL20C.GL_COMPILE_STATUS) == 0) {
            GL20C.glDeleteShader(vs);
            GL20C.glDeleteShader(fs);
            return 0;
        }
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            GL20C.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    public static boolean isDepthStencilFormat(int internalFormat) {
        return internalFormat == GL30C.GL_DEPTH24_STENCIL8 || internalFormat == GL30C.GL_DEPTH32F_STENCIL8;
    }

    private static int normalizeDepthFormat(int fmt) {
        return switch (fmt) {
            case GL14C.GL_DEPTH_COMPONENT16, GL14C.GL_DEPTH_COMPONENT24, GL14C.GL_DEPTH_COMPONENT32,
                 GL30C.GL_DEPTH_COMPONENT32F, GL30C.GL_DEPTH24_STENCIL8, GL30C.GL_DEPTH32F_STENCIL8 -> fmt;
            case GL11C.GL_DEPTH_COMPONENT -> GL14C.GL_DEPTH_COMPONENT24;
            case GL30C.GL_DEPTH_STENCIL -> GL30C.GL_DEPTH24_STENCIL8;
            default -> GL14C.GL_DEPTH_COMPONENT24;
        };
    }

    private void ensureTextures(int w, int h, int targetDepthFormat, boolean needColor) {
        boolean resized = (w != width || h != height || targetDepthFormat != depthInternalFormat
                || depthTex == 0 || captureFbo == 0);
        boolean needNewColor = needColor && colorTex == 0;
        if (!resized && !needNewColor) return;

        if (resized) {
            disposeAttachments();
            width = w;
            height = h;
            depthInternalFormat = targetDepthFormat;
            depthTex = GL11C.glGenTextures();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
            int pixelFormat;
            int pixelType;
            if (targetDepthFormat == GL30C.GL_DEPTH24_STENCIL8) {
                pixelFormat = GL30C.GL_DEPTH_STENCIL;
                pixelType = GL30C.GL_UNSIGNED_INT_24_8;
            } else if (targetDepthFormat == GL30C.GL_DEPTH32F_STENCIL8) {
                pixelFormat = GL30C.GL_DEPTH_STENCIL;
                pixelType = GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            } else if (targetDepthFormat == GL30C.GL_DEPTH_COMPONENT32F) {
                pixelFormat = GL11C.GL_DEPTH_COMPONENT;
                pixelType = GL11C.GL_FLOAT;
            } else {
                pixelFormat = GL11C.GL_DEPTH_COMPONENT;
                pixelType = GL11C.GL_UNSIGNED_INT;
            }
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, targetDepthFormat, w, h, 0,
                    pixelFormat, pixelType, (ByteBuffer) null);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

            captureFbo = GL30C.glGenFramebuffers();
            int prevDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, captureFbo);
            int attachPoint = isDepthStencilFormat(targetDepthFormat)
                    ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachPoint,
                    GL11C.GL_TEXTURE_2D, depthTex, 0);
            // Depth-only FBO requires GL_NONE draw/read buffers for completeness on strict OpenGL 3.2 Core.
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            GL11C.glReadBuffer(GL11C.GL_NONE);
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
            GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
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

    private void disposeAttachments() {
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

    public void dispose() {
        disposeAttachments();
        if (resolve2dProg != 0) {
            GL20C.glDeleteProgram(resolve2dProg);
            resolve2dProg = 0;
        }
        if (resolveArrayProg != 0) {
            GL20C.glDeleteProgram(resolveArrayProg);
            resolveArrayProg = 0;
        }
        if (resolveVao != 0) {
            GL30C.glDeleteVertexArrays(resolveVao);
            resolveVao = 0;
        }
    }
}
