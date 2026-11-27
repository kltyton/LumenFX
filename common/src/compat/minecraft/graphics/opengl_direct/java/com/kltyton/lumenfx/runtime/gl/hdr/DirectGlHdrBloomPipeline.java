package com.kltyton.lumenfx.runtime.gl.hdr;

import com.kltyton.lumenfx.runtime.gl.depth.DirectGlSceneDepthCapture;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Native OpenGL 3.3+ HDR Bloom FBO pipeline for non-26.2 targets (1.20.1, 1.21.1, 26.1.2).
 *
 * <p>Accumulates HDR emissive effects into a {@code GL_RGBA16F} offscreen buffer sharing depth occlusion
 * from Minecraft's active framebuffer, performs soft-knee bright-pass extraction, runs a 5-level Kawase
 * downsample/upsample bloom pyramid, and composites ACES-tonemapped HDR + bloom cleanly back onto the
 * caller's active framebuffer.</p>
 */
public final class DirectGlHdrBloomPipeline {
    private static final int MIP_LEVELS = 5;

    private static final String FULLSCREEN_VERT = """
            #version 150 core
            out vec2 vUv;
            void main() {
                vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = pos;
                gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String PREFILTER_FRAG = """
            #version 150 core
            in vec2 vUv;
            uniform sampler2D uSource;
            uniform float uThreshold;
            uniform float uKnee;
            out vec4 fragColor;
            void main() {
                vec3 c = texture(uSource, vUv).rgb;
                float brightness = max(c.r, max(c.g, c.b));
                float soft = clamp(brightness - uThreshold + uKnee, 0.0, 2.0 * uKnee);
                soft = (soft * soft) / max(4.0 * uKnee, 0.0001);
                float contribution = max(soft, brightness - uThreshold) / max(brightness, 0.0001);
                fragColor = vec4(c * max(contribution, 0.0), 1.0);
            }
            """;

    private static final String DOWNSAMPLE_FRAG = """
            #version 150 core
            in vec2 vUv;
            uniform sampler2D uSource;
            uniform vec2 uTexelSize;
            out vec4 fragColor;
            void main() {
                vec2 d = uTexelSize;
                vec3 c = texture(uSource, vUv).rgb * 0.25;
                c += texture(uSource, vUv + vec2(-d.x, -d.y)).rgb * 0.125;
                c += texture(uSource, vUv + vec2( d.x, -d.y)).rgb * 0.125;
                c += texture(uSource, vUv + vec2(-d.x,  d.y)).rgb * 0.125;
                c += texture(uSource, vUv + vec2( d.x,  d.y)).rgb * 0.125;
                c += texture(uSource, vUv + vec2(-2.0 * d.x, 0.0)).rgb * 0.0625;
                c += texture(uSource, vUv + vec2( 2.0 * d.x, 0.0)).rgb * 0.0625;
                c += texture(uSource, vUv + vec2(0.0, -2.0 * d.y)).rgb * 0.0625;
                c += texture(uSource, vUv + vec2(0.0,  2.0 * d.y)).rgb * 0.0625;
                fragColor = vec4(c, 1.0);
            }
            """;

    private static final String UPSAMPLE_FRAG = """
            #version 150 core
            in vec2 vUv;
            uniform sampler2D uSource;
            uniform vec2 uTexelSize;
            uniform float uRadius;
            out vec4 fragColor;
            void main() {
                vec2 d = uTexelSize * uRadius;
                vec3 c = texture(uSource, vUv).rgb * (4.0 / 16.0);
                c += (texture(uSource, vUv + vec2(-d.x, 0.0)).rgb
                    + texture(uSource, vUv + vec2( d.x, 0.0)).rgb
                    + texture(uSource, vUv + vec2(0.0, -d.y)).rgb
                    + texture(uSource, vUv + vec2(0.0,  d.y)).rgb) * (2.0 / 16.0);
                c += (texture(uSource, vUv + vec2(-d.x, -d.y)).rgb
                    + texture(uSource, vUv + vec2( d.x, -d.y)).rgb
                    + texture(uSource, vUv + vec2(-d.x,  d.y)).rgb
                    + texture(uSource, vUv + vec2( d.x,  d.y)).rgb) * (1.0 / 16.0);
                fragColor = vec4(c, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAG = """
            #version 150 core
            in vec2 vUv;
            uniform sampler2D uHdrColor;
            uniform sampler2D uBloom;
            uniform float uBloomStrength;
            out vec4 fragColor;
            vec3 acesFilm(vec3 x) {
                const float a = 2.51;
                const float b = 0.03;
                const float c = 2.43;
                const float d = 0.59;
                const float e = 0.14;
                return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
            }
            void main() {
                vec4 hdr = texture(uHdrColor, vUv);
                vec3 bloom = texture(uBloom, vUv).rgb * uBloomStrength;
                vec3 combined = hdr.rgb + bloom;
                float peak = max(combined.r, max(combined.g, combined.b));
                if (peak <= 0.0005 && hdr.a <= 0.0005) discard;
                vec3 mapped = acesFilm(combined);
                // Keep output alpha strictly to opaque/alpha-blended coverage (hdr.a) so purely additive
                // HDR particles and outer bloom halos (hdr.a == 0) add cleanly over bright sky without darkening.
                fragColor = vec4(mapped, clamp(hdr.a, 0.0, 1.0));
            }
            """;

    private int hdrFbo;
    private int hdrColorTex;
    private int hdrDepthRbo;
    private final int[] mipFbos = new int[MIP_LEVELS];
    private final int[] mipTex = new int[MIP_LEVELS];
    private final int[] mipW = new int[MIP_LEVELS];
    private final int[] mipH = new int[MIP_LEVELS];
    private int emptyVao;

    private int prefilterProg;
    private int downsampleProg;
    private int upsampleProg;
    private int compositeProg;

    private int width;
    private int height;
    private int depthInternalFormat;
    private boolean supported = true;
    private boolean activePass;
    private int savedTargetFbo;
    private float threshold = 0.9f;
    private float knee = 0.5f;
    private float bloomStrength = 0.65f;
    private float bloomRadius = 1.25f;

    public boolean isSupported() { return supported; }
    public void setBloomStrength(float value) { this.bloomStrength = Math.max(0f, Math.min(4f, value)); }
    public void setThreshold(float value) { this.threshold = Math.max(0.05f, value); }

    /**
     * Begins an offscreen HDR accumulation pass sharing the scene's depth buffer.
     * Returns true if the HDR FBO is active and caller should call {@link #endAndComposite} after drawing HDR effects.
     */
    public boolean beginHdrPass(int targetDrawFbo, int vpWidth, int vpHeight) {
        if (!supported || vpWidth < 16 || vpHeight < 16) return false;
        int srcDepthFormat = DirectGlSceneDepthCapture.queryDepthInternalFormat(targetDrawFbo);
        if (!ensureResources(vpWidth, vpHeight, srcDepthFormat)) {
            supported = false;
            return false;
        }
        savedTargetFbo = targetDrawFbo;
        // Copy scene depth from Minecraft's active FBO into our HDR FBO so HDR effects occlude accurately
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, targetDrawFbo);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, hdrFbo);
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }
        GL30C.glBlitFramebuffer(0, 0, vpWidth, vpHeight, 0, 0, vpWidth, vpHeight,
                GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) { }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, hdrFbo);
        GL11C.glViewport(0, 0, vpWidth, vpHeight);
        GL11C.glColorMask(true, true, true, true);
        GL11C.glClearColor(0f, 0f, 0f, 0f);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        activePass = true;
        return true;
    }

    /** Runs the Kawase bloom pyramid and composites HDR + bloom additively onto the saved target FBO. */
    public void endAndComposite() {
        if (!activePass) return;
        activePass = false;

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL30C.glBindVertexArray(emptyVao);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);

        // 1. Bright-pass prefilter into mip 0
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mipFbos[0]);
        GL11C.glViewport(0, 0, mipW[0], mipH[0]);
        GL20C.glUseProgram(prefilterProg);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, hdrColorTex);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(prefilterProg, "uSource"), 0);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(prefilterProg, "uThreshold"), threshold);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(prefilterProg, "uKnee"), knee);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        // 2. Downsample mip 0 -> 1 -> ... -> MIP_LEVELS-1
        GL20C.glUseProgram(downsampleProg);
        int downSourceLoc = GL20C.glGetUniformLocation(downsampleProg, "uSource");
        int downTexelLoc = GL20C.glGetUniformLocation(downsampleProg, "uTexelSize");
        GL20C.glUniform1i(downSourceLoc, 0);
        for (int i = 1; i < MIP_LEVELS; i++) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mipFbos[i]);
            GL11C.glViewport(0, 0, mipW[i], mipH[i]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mipTex[i - 1]);
            GL20C.glUniform2f(downTexelLoc, 1.0f / mipW[i - 1], 1.0f / mipH[i - 1]);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        }

        // 3. Additive tent upsample MIP_LEVELS-1 -> ... -> 0
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
        GL20C.glUseProgram(upsampleProg);
        int upSourceLoc = GL20C.glGetUniformLocation(upsampleProg, "uSource");
        int upTexelLoc = GL20C.glGetUniformLocation(upsampleProg, "uTexelSize");
        int upRadiusLoc = GL20C.glGetUniformLocation(upsampleProg, "uRadius");
        GL20C.glUniform1i(upSourceLoc, 0);
        GL20C.glUniform1f(upRadiusLoc, bloomRadius);
        for (int i = MIP_LEVELS - 1; i > 0; i--) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mipFbos[i - 1]);
            GL11C.glViewport(0, 0, mipW[i - 1], mipH[i - 1]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mipTex[i]);
            GL20C.glUniform2f(upTexelLoc, 1.0f / mipW[i], 1.0f / mipH[i]);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        }

        // 4. Composite ACES HDR + Bloom onto caller's framebuffer
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, savedTargetFbo);
        GL11C.glViewport(0, 0, width, height);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        GL20C.glUseProgram(compositeProg);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, hdrColorTex);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(compositeProg, "uHdrColor"), 0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mipTex[0]);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(compositeProg, "uBloom"), 1);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(compositeProg, "uBloomStrength"), bloomStrength);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL30C.glBindVertexArray(0);
    }

    private boolean ensureResources(int w, int h, int targetDepthFormat) {
        if (prefilterProg == 0) {
            prefilterProg = link(FULLSCREEN_VERT, PREFILTER_FRAG);
            downsampleProg = link(FULLSCREEN_VERT, DOWNSAMPLE_FRAG);
            upsampleProg = link(FULLSCREEN_VERT, UPSAMPLE_FRAG);
            compositeProg = link(FULLSCREEN_VERT, COMPOSITE_FRAG);
            emptyVao = GL30C.glGenVertexArrays();
        }
        if (w == width && h == height && targetDepthFormat == depthInternalFormat && hdrFbo != 0) return true;
        disposeFramebuffers();
        width = w;
        height = h;
        depthInternalFormat = targetDepthFormat;

        hdrColorTex = createRgba16fTexture(w, h);
        hdrDepthRbo = GL30C.glGenRenderbuffers();
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, hdrDepthRbo);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, targetDepthFormat, w, h);

        hdrFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, hdrFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, hdrColorTex, 0);
        int depthAttach = DirectGlSceneDepthCapture.isDepthStencilFormat(targetDepthFormat)
                ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, depthAttach,
                GL30C.GL_RENDERBUFFER, hdrDepthRbo);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            disposeFramebuffers();
            return false;
        }

        int mw = Math.max(1, w / 2);
        int mh = Math.max(1, h / 2);
        for (int i = 0; i < MIP_LEVELS; i++) {
            mipW[i] = mw;
            mipH[i] = mh;
            mipTex[i] = createRgba16fTexture(mw, mh);
            mipFbos[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mipFbos[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, mipTex[i], 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                disposeFramebuffers();
                return false;
            }
            mw = Math.max(1, mw / 2);
            mh = Math.max(1, mh / 2);
        }
        return true;
    }

    private static int createRgba16fTexture(int w, int h) {
        int tex = GL11C.glGenTextures();
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, w, h, 0,
                GL11C.GL_RGBA, GL30C.GL_HALF_FLOAT, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static int link(String vertSrc, String fragSrc) {
        int vs = compile(GL20C.GL_VERTEX_SHADER, vertSrc);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, fragSrc);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL30C.glBindFragDataLocation(prog, 0, "fragColor");
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog, 4096);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("Failed to link LumenFX HDR Bloom shader: " + log);
        }
        return prog;
    }

    private static int compile(int type, String src) {
        int sh = GL20C.glCreateShader(type);
        GL20C.glShaderSource(sh, src);
        GL20C.glCompileShader(sh);
        if (GL20C.glGetShaderi(sh, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(sh, 4096);
            GL20C.glDeleteShader(sh);
            throw new IllegalStateException("Failed to compile LumenFX HDR Bloom shader: " + log);
        }
        return sh;
    }

    private void disposeFramebuffers() {
        if (hdrFbo != 0) { GL30C.glDeleteFramebuffers(hdrFbo); hdrFbo = 0; }
        if (hdrColorTex != 0) { GL11C.glDeleteTextures(hdrColorTex); hdrColorTex = 0; }
        if (hdrDepthRbo != 0) { GL30C.glDeleteRenderbuffers(hdrDepthRbo); hdrDepthRbo = 0; }
        for (int i = 0; i < MIP_LEVELS; i++) {
            if (mipFbos[i] != 0) { GL30C.glDeleteFramebuffers(mipFbos[i]); mipFbos[i] = 0; }
            if (mipTex[i] != 0) { GL11C.glDeleteTextures(mipTex[i]); mipTex[i] = 0; }
        }
        width = 0;
        height = 0;
    }

    public void dispose() {
        disposeFramebuffers();
        if (emptyVao != 0) { GL30C.glDeleteVertexArrays(emptyVao); emptyVao = 0; }
        if (prefilterProg != 0) { GL20C.glDeleteProgram(prefilterProg); prefilterProg = 0; }
        if (downsampleProg != 0) { GL20C.glDeleteProgram(downsampleProg); downsampleProg = 0; }
        if (upsampleProg != 0) { GL20C.glDeleteProgram(upsampleProg); upsampleProg = 0; }
        if (compositeProg != 0) { GL20C.glDeleteProgram(compositeProg); compositeProg = 0; }
    }
}
