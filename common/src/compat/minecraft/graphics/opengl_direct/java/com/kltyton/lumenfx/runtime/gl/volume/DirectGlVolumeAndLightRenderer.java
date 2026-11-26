package com.kltyton.lumenfx.runtime.gl.volume;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Native OpenGL 3.3+ renderer for 3D raymarched Volumetric Fog / Materials, Screen-Space Deferred Decals,
 * and Dynamic Point / Spot Lights reconstructing world positions from captured {@code SceneDepth}.
 */
public final class DirectGlVolumeAndLightRenderer {
    public record VolumeSpec(double x, double y, double z, float sx, float sy, float sz,
                             float r, float g, float b, float density, float anisotropy) { }

    public record DecalSpec(double x, double y, double z, float sx, float sy, float sz,
                            float r, float g, float b, float a, int textureId) { }

    public record LightSpec(double x, double y, double z, float radius,
                            float r, float g, float b, float intensity,
                            boolean spot, float dirX, float dirY, float dirZ, float cosOuterCone) { }

    private static final String BOX_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            out vec3 vLocalPos;
            out vec3 vWorldRel;
            void main() {
                vLocalPos = aPos;
                vec4 wr = uModel * vec4(aPos, 1.0);
                vWorldRel = wr.xyz;
                gl_Position = uViewProj * wr;
            }
            """;

    private static final String VOLUME_FRAG = """
            #version 330 core
            in vec3 vLocalPos;
            in vec3 vWorldRel;
            uniform mat4 uInvModel;
            uniform sampler2D uSceneDepth;
            uniform mat4 uInvViewProj;
            uniform vec2 uInvViewport;
            uniform vec4 uColorDensity;
            uniform float uTime;
            uniform float uAnisotropy;
            out vec4 fragColor;
            float hash3(vec3 p) {
                p = fract(p * 0.3183099 + 0.1);
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }
            float noise3(vec3 x) {
                vec3 i = floor(x);
                vec3 f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(
                    mix(mix(hash3(i + vec3(0,0,0)), hash3(i + vec3(1,0,0)), f.x),
                        mix(hash3(i + vec3(0,1,0)), hash3(i + vec3(1,1,0)), f.x), f.y),
                    mix(mix(hash3(i + vec3(0,0,1)), hash3(i + vec3(1,0,1)), f.x),
                        mix(hash3(i + vec3(0,1,1)), hash3(i + vec3(1,1,1)), f.x), f.y), f.z);
            }
            void main() {
                vec3 rayDir = normalize(vWorldRel);
                vec3 localRo = (uInvModel * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
                vec3 localRd = normalize((uInvModel * vec4(rayDir, 0.0)).xyz);
                vec3 invRd = 1.0 / max(abs(localRd), vec3(1e-5)) * sign(localRd);
                vec3 t0 = (-vec3(0.5) - localRo) * invRd;
                vec3 t1 = ( vec3(0.5) - localRo) * invRd;
                vec3 tmin = min(t0, t1);
                vec3 tmax = max(t0, t1);
                float tNear = max(0.0, max(tmin.x, max(tmin.y, tmin.z)));
                float tFar = min(tmax.x, min(tmax.y, tmax.z));
                if (tFar <= tNear) discard;

                vec2 screenUv = gl_FragCoord.xy * uInvViewport;
                float depth = texture(uSceneDepth, screenUv).r;
                vec4 sceneClip = uInvViewProj * vec4(screenUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec3 sceneLocal = (uInvModel * vec4(sceneClip.xyz / max(sceneClip.w, 1e-5), 1.0)).xyz;
                float maxDist = dot(sceneLocal - localRo, localRd);
                tFar = min(tFar, maxDist);
                if (tFar <= tNear) discard;

                const int STEPS = 16;
                float dt = (tFar - tNear) / float(STEPS);
                float transmittance = 1.0;
                vec3 accum = vec3(0.0);
                vec3 lightDir = normalize(vec3(0.35, 0.85, 0.40));
                float cosTheta = dot(rayDir, lightDir);
                float g = clamp(uAnisotropy, -0.8, 0.8);
                float phase = (1.0 - g*g) / (4.0 * 3.14159 * pow(max(0.05, 1.0 + g*g - 2.0*g*cosTheta), 1.5));
                for (int i = 0; i < STEPS; i++) {
                    vec3 p = localRo + localRd * (tNear + (float(i) + 0.5) * dt);
                    float edge = clamp(1.0 - length(p * 2.0), 0.0, 1.0);
                    float n = noise3(p * 4.0 + vec3(0.0, -uTime * 0.4, uTime * 0.25));
                    float sigma = uColorDensity.a * edge * (0.45 + 0.55 * n);
                    float stepTrans = exp(-sigma * dt * 2.5);
                    accum += transmittance * uColorDensity.rgb * (0.6 + 0.8 * phase) * (1.0 - stepTrans);
                    transmittance *= stepTrans;
                    if (transmittance < 0.01) break;
                }
                float alpha = 1.0 - transmittance;
                if (alpha <= 0.004) discard;
                fragColor = vec4(accum, alpha);
            }
            """;

    private static final String DECAL_FRAG = """
            #version 330 core
            uniform mat4 uInvModel;
            uniform mat4 uInvViewProj;
            uniform sampler2D uSceneDepth;
            uniform sampler2D uTex;
            uniform vec2 uInvViewport;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                vec2 screenUv = gl_FragCoord.xy * uInvViewport;
                float depth = texture(uSceneDepth, screenUv).r;
                if (depth >= 0.99999) discard;
                vec4 worldRel = uInvViewProj * vec4(screenUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec3 local = (uInvModel * vec4(worldRel.xyz / max(worldRel.w, 1e-5), 1.0)).xyz;
                if (abs(local.x) > 0.5 || abs(local.y) > 0.5 || abs(local.z) > 0.5) discard;
                vec2 decalUv = local.xz + 0.5;
                float edgeFade = smoothstep(0.5, 0.42, abs(local.y));
                vec4 tex = texture(uTex, decalUv) * uColor;
                tex.a *= edgeFade;
                if (tex.a <= 0.005) discard;
                fragColor = tex;
            }
            """;

    private static final String LIGHT_FRAG = """
            #version 330 core
            uniform mat4 uInvViewProj;
            uniform sampler2D uSceneDepth;
            uniform vec2 uInvViewport;
            uniform vec3 uLightPos;
            uniform float uRadius;
            uniform vec4 uLightColor;
            uniform vec4 uSpotDirCos;
            out vec4 fragColor;
            void main() {
                vec2 screenUv = gl_FragCoord.xy * uInvViewport;
                float depth = texture(uSceneDepth, screenUv).r;
                if (depth >= 0.99999) discard;
                vec4 worldRel = uInvViewProj * vec4(screenUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec3 p = worldRel.xyz / max(worldRel.w, 1e-5);
                vec3 toSurf = p - uLightPos;
                float dist = length(toSurf);
                if (dist >= uRadius) discard;
                float falloff = 1.0 - dist / uRadius;
                float atten = falloff * falloff;
                if (uSpotDirCos.w > -0.99) {
                    float cosAng = dot(normalize(toSurf), normalize(uSpotDirCos.xyz));
                    atten *= smoothstep(uSpotDirCos.w, min(1.0, uSpotDirCos.w + 0.15), cosAng);
                }
                vec3 rgb = uLightColor.rgb * (uLightColor.a * atten);
                if (max(rgb.r, max(rgb.g, rgb.b)) <= 0.002) discard;
                fragColor = vec4(rgb, 1.0);
            }
            """;

    private int volumeProg;
    private int decalProg;
    private int lightProg;
    private int cubeVao;
    private int cubeVbo;
    private int cubeEbo;
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);
    private final Matrix4f modelMat = new Matrix4f();
    private final Matrix4f invModelMat = new Matrix4f();
    private final Matrix4f invViewProj = new Matrix4f();

    public void renderVolume(VolumeSpec v, Matrix4f viewProj, double camX, double camY, double camZ,
                             int sceneDepthTex, int vpW, int vpH, float timeSeconds) {
        if (sceneDepthTex == 0) return;
        ensureResources();
        prepareTransforms(viewProj, v.x() - camX, v.y() - camY, v.z() - camZ, v.sx(), v.sy(), v.sz());

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glEnable(GL11C.GL_CULL_FACE);
        GL11C.glCullFace(GL11C.GL_FRONT);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        GL20C.glUseProgram(volumeProg);
        uploadMatrices(volumeProg, viewProj);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneDepthTex);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(volumeProg, "uSceneDepth"), 1);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(volumeProg, "uInvViewport"), 1f / Math.max(1, vpW), 1f / Math.max(1, vpH));
        GL20C.glUniform4f(GL20C.glGetUniformLocation(volumeProg, "uColorDensity"), v.r(), v.g(), v.b(), v.density());
        GL20C.glUniform1f(GL20C.glGetUniformLocation(volumeProg, "uTime"), timeSeconds);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(volumeProg, "uAnisotropy"), v.anisotropy());
        drawBox();
        GL11C.glCullFace(GL11C.GL_BACK);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    public void renderDecal(DecalSpec d, Matrix4f viewProj, double camX, double camY, double camZ,
                            int sceneDepthTex, int vpW, int vpH) {
        if (sceneDepthTex == 0) return;
        ensureResources();
        prepareTransforms(viewProj, d.x() - camX, d.y() - camY, d.z() - camZ, d.sx(), d.sy(), d.sz());

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glEnable(GL11C.GL_CULL_FACE);
        GL11C.glCullFace(GL11C.GL_FRONT);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        GL20C.glUseProgram(decalProg);
        uploadMatrices(decalProg, viewProj);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, d.textureId());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(decalProg, "uTex"), 0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneDepthTex);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(decalProg, "uSceneDepth"), 1);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(decalProg, "uInvViewport"), 1f / Math.max(1, vpW), 1f / Math.max(1, vpH));
        GL20C.glUniform4f(GL20C.glGetUniformLocation(decalProg, "uColor"), d.r(), d.g(), d.b(), d.a());
        drawBox();
        GL11C.glCullFace(GL11C.GL_BACK);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    public void renderLight(LightSpec l, Matrix4f viewProj, double camX, double camY, double camZ,
                            int sceneDepthTex, int vpW, int vpH) {
        if (sceneDepthTex == 0 || l.radius() <= 0.05f || l.intensity() <= 0.001f) return;
        ensureResources();
        float d = l.radius() * 2.0f;
        prepareTransforms(viewProj, l.x() - camX, l.y() - camY, l.z() - camZ, d, d, d);

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glEnable(GL11C.GL_CULL_FACE);
        GL11C.glCullFace(GL11C.GL_FRONT);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);

        GL20C.glUseProgram(lightProg);
        uploadMatrices(lightProg, viewProj);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneDepthTex);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(lightProg, "uSceneDepth"), 1);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(lightProg, "uInvViewport"), 1f / Math.max(1, vpW), 1f / Math.max(1, vpH));
        GL20C.glUniform3f(GL20C.glGetUniformLocation(lightProg, "uLightPos"),
                (float) (l.x() - camX), (float) (l.y() - camY), (float) (l.z() - camZ));
        GL20C.glUniform1f(GL20C.glGetUniformLocation(lightProg, "uRadius"), l.radius());
        GL20C.glUniform4f(GL20C.glGetUniformLocation(lightProg, "uLightColor"), l.r(), l.g(), l.b(), l.intensity());
        GL20C.glUniform4f(GL20C.glGetUniformLocation(lightProg, "uSpotDirCos"),
                l.dirX(), l.dirY(), l.dirZ(), l.spot() ? l.cosOuterCone() : -2.0f);
        drawBox();
        GL11C.glCullFace(GL11C.GL_BACK);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    private void prepareTransforms(Matrix4f viewProj, double rx, double ry, double rz, float sx, float sy, float sz) {
        modelMat.identity().translate((float) rx, (float) ry, (float) rz)
                .scale(Math.max(0.05f, sx), Math.max(0.05f, sy), Math.max(0.05f, sz));
        invModelMat.set(modelMat).invert();
        invViewProj.set(viewProj).invert();
    }

    private void uploadMatrices(int prog, Matrix4f viewProj) {
        matBuf.clear(); viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(prog, "uViewProj"), false, matBuf);
        matBuf.clear(); modelMat.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(prog, "uModel"), false, matBuf);
        int invMLoc = GL20C.glGetUniformLocation(prog, "uInvModel");
        if (invMLoc >= 0) {
            matBuf.clear(); invModelMat.get(matBuf);
            GL20C.glUniformMatrix4fv(invMLoc, false, matBuf);
        }
        int invVpLoc = GL20C.glGetUniformLocation(prog, "uInvViewProj");
        if (invVpLoc >= 0) {
            matBuf.clear(); invViewProj.get(matBuf);
            GL20C.glUniformMatrix4fv(invVpLoc, false, matBuf);
        }
    }

    private void drawBox() {
        GL30C.glBindVertexArray(cubeVao);
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, cubeEbo);
        GL11C.glDrawElements(GL11C.GL_TRIANGLES, 36, GL11C.GL_UNSIGNED_INT, 0L);
        GL30C.glBindVertexArray(0);
    }

    private void ensureResources() {
        if (volumeProg != 0) return;
        volumeProg = link(BOX_VERT, VOLUME_FRAG);
        decalProg = link(BOX_VERT, DECAL_FRAG);
        lightProg = link(BOX_VERT, LIGHT_FRAG);

        float[] verts = {
                -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f,
                -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f
        };
        int[] idx = {
                0, 2, 1, 0, 3, 2,  4, 5, 6, 4, 6, 7,
                0, 1, 5, 0, 5, 4,  2, 3, 7, 2, 7, 6,
                0, 4, 7, 0, 7, 3,  1, 2, 6, 1, 6, 5
        };
        cubeVao = GL30C.glGenVertexArrays();
        cubeVbo = GL15C.glGenBuffers();
        cubeEbo = GL15C.glGenBuffers();
        FloatBuffer vb = MemoryUtil.memAllocFloat(verts.length);
        IntBuffer ib = MemoryUtil.memAllocInt(idx.length);
        try {
            vb.put(verts).flip();
            ib.put(idx).flip();
            GL30C.glBindVertexArray(cubeVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, cubeVbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vb, GL15C.GL_STATIC_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, cubeEbo);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, ib, GL15C.GL_STATIC_DRAW);
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, 3 * Float.BYTES, 0L);
            GL30C.glBindVertexArray(0);
        } finally {
            MemoryUtil.memFree(vb);
            MemoryUtil.memFree(ib);
        }
    }

    private static int link(String v, String f) {
        int vs = compile(GL20C.GL_VERTEX_SHADER, v);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, f);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        return prog;
    }

    private static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        return s;
    }

    public void dispose() {
        if (volumeProg != 0) { GL20C.glDeleteProgram(volumeProg); volumeProg = 0; }
        if (decalProg != 0) { GL20C.glDeleteProgram(decalProg); decalProg = 0; }
        if (lightProg != 0) { GL20C.glDeleteProgram(lightProg); lightProg = 0; }
        if (cubeVao != 0) { GL30C.glDeleteVertexArrays(cubeVao); cubeVao = 0; }
        if (cubeVbo != 0) { GL15C.glDeleteBuffers(cubeVbo); cubeVbo = 0; }
        if (cubeEbo != 0) { GL15C.glDeleteBuffers(cubeEbo); cubeEbo = 0; }
        MemoryUtil.memFree(matBuf);
    }
}
