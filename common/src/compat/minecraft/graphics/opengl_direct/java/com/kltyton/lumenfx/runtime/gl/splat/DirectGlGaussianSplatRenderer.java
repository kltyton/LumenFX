package com.kltyton.lumenfx.runtime.gl.splat;

import com.kltyton.lumenfx.runtime.model.GaussianSplatModel.Splat;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * Native OpenGL 3.3+ 3D Gaussian Splatting renderer projecting 3D anisotropic ellipsoids
 * ({@code Sigma = R * S * S^T * R^T}) into screen-space 2D Gaussian eigenvectors with
 * back-to-front depth sorting and exact {@code exp(-0.5 * r^2)} falloff.
 */
public final class DirectGlGaussianSplatRenderer {
    private static final int FLOATS_PER_SPLAT = 14; // pos(3), scale(3), quat(4), rgba(4)

    private static final String SPLAT_VERT = """
            #version 150 core
            in vec3 aCenter;
            in vec3 aScale;
            in vec4 aQuat;
            in vec4 aColor;
            uniform mat4 uView;
            uniform mat4 uProj;
            uniform vec2 uViewport;
            out vec2 vConicOffset;
            out vec4 vColor;
            const vec2 CORNERS[6] = vec2[6](
                vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
            );
            mat3 quatToMat3(vec4 q) {
                float x = q.x, y = q.y, z = q.z, w = q.w;
                return mat3(
                    1.0 - 2.0*(y*y + z*z), 2.0*(x*y + z*w),       2.0*(x*z - y*w),
                    2.0*(x*y - z*w),       1.0 - 2.0*(x*x + z*z), 2.0*(y*z + x*w),
                    2.0*(x*z + y*w),       2.0*(y*z - x*w),       1.0 - 2.0*(x*x + y*y)
                );
            }
            void main() {
                vec4 viewPos = uView * vec4(aCenter, 1.0);
                if (viewPos.z >= -0.05) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    vConicOffset = vec2(10.0);
                    vColor = vec4(0.0);
                    return;
                }
                vec4 clip = uProj * viewPos;
                vec3 ndc = clip.xyz / clip.w;
                mat3 R = quatToMat3(normalize(aQuat));
                mat3 M = mat3(R[0] * aScale.x, R[1] * aScale.y, R[2] * aScale.z);
                mat3 Sigma3D = M * transpose(M);
                mat3 W = mat3(uView);
                float fx = uProj[0][0] * uViewport.x * 0.5;
                float fy = uProj[1][1] * uViewport.y * 0.5;
                float invZ = 1.0 / (-viewPos.z);
                mat3 J = mat3(
                    fx * invZ, 0.0, 0.0,
                    0.0, fy * invZ, 0.0,
                    -fx * viewPos.x * invZ * invZ, -fy * viewPos.y * invZ * invZ, 0.0
                );
                mat3 T = J * W;
                mat3 cov = T * Sigma3D * transpose(T);
                float a = cov[0][0] + 0.3;
                float b = cov[0][1];
                float c = cov[1][1] + 0.3;
                float mid = 0.5 * (a + c);
                float disc = sqrt(max(0.0001, (a - c) * (a - c) * 0.25 + b * b));
                float lambda1 = max(0.1, mid + disc);
                float lambda2 = max(0.1, mid - disc);
                vec2 majorDir = abs(b) > 1e-5 ? normalize(vec2(lambda1 - c, b)) : (a >= c ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
                vec2 minorDir = vec2(-majorDir.y, majorDir.x);
                float rad1 = min(sqrt(lambda1) * 3.0, max(uViewport.x, uViewport.y) * 0.5);
                float rad2 = min(sqrt(lambda2) * 3.0, max(uViewport.x, uViewport.y) * 0.5);

                vec2 corner = CORNERS[gl_VertexID % 6];
                vec2 pxOffset = majorDir * (corner.x * rad1) + minorDir * (corner.y * rad2);
                vec2 ndcOffset = (pxOffset * 2.0) / max(uViewport, vec2(1.0));
                gl_Position = vec4(ndc.xy + ndcOffset, ndc.z, 1.0);
                vConicOffset = corner * 3.0;
                vColor = aColor;
            }
            """;

    private static final String SPLAT_FRAG = """
            #version 150 core
            in vec2 vConicOffset;
            in vec4 vColor;
            uniform float uHdr;
            out vec4 fragColor;
            void main() {
                float r2 = dot(vConicOffset, vConicOffset);
                if (r2 > 9.0) discard;
                float alpha = vColor.a * exp(-0.5 * r2);
                if (alpha < 0.004) discard;
                fragColor = vec4(vColor.rgb * uHdr * alpha, alpha);
            }
            """;

    private static final int DIVISOR_NONE = 0;
    private static final int DIVISOR_GL33 = 1;
    private static final int DIVISOR_ARB = 2;

    private int program;
    private int vao;
    private int instanceVbo;
    private int capacitySplats;
    private int divisorMode = DIVISOR_NONE;
    private FloatBuffer staging;
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);
    private long[] sortPairs = new long[0];

    public void renderSplats(List<Splat> splats, double originX, double originY, double originZ,
                             float scale, float tintR, float tintG, float tintB, float tintA, boolean hdr,
                             Matrix4f proj, Matrix4f modelView,
                             double camX, double camY, double camZ, int vpWidth, int vpHeight) {
        int n = splats.size();
        if (n <= 0) return;
        ensureResources(n);

        // Sort back-to-front by squared distance from camera
        if (sortPairs.length < n) sortPairs = new long[Math.max(n, sortPairs.length * 2)];
        float ox = (float) (originX - camX);
        float oy = (float) (originY - camY);
        float oz = (float) (originZ - camZ);
        for (int i = 0; i < n; i++) {
            Splat s = splats.get(i);
            float dx = ox + s.x() * scale;
            float dy = oy + s.y() * scale;
            float dz = oz + s.z() * scale;
            float distSq = dx * dx + dy * dy + dz * dz;
            int key = ~Float.floatToRawIntBits(distSq); // descending (farthest first)
            sortPairs[i] = ((long) key << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(sortPairs, 0, n);

        boolean instanced = divisorMode != DIVISOR_NONE;
        staging.clear();
        for (int i = 0; i < n; i++) {
            Splat s = splats.get((int) sortPairs[i]);
            float cx = ox + s.x() * scale;
            float cy = oy + s.y() * scale;
            float cz = oz + s.z() * scale;
            float sx = Math.max(0.005f, s.sx() * scale);
            float sy = Math.max(0.005f, s.sy() * scale);
            float sz = Math.max(0.005f, s.sz() * scale);
            float qx = s.qx(), qy = s.qy(), qz = s.qz(), qw = s.qw();
            float r = s.r() * tintR;
            float g = s.g() * tintG;
            float b = s.b() * tintB;
            float a = s.opacity() * tintA;
            int repeats = instanced ? 1 : 6;
            for (int k = 0; k < repeats; k++) {
                staging.put(cx).put(cy).put(cz);
                staging.put(sx).put(sy).put(sz);
                staging.put(qx).put(qy).put(qz).put(qw);
                staging.put(r).put(g).put(b).put(a);
            }
        }
        staging.flip();

        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        GL20C.glUseProgram(program);
        matBuf.clear(); modelView.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "uView"), false, matBuf);
        matBuf.clear(); proj.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "uProj"), false, matBuf);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(program, "uViewport"), Math.max(1, vpWidth), Math.max(1, vpHeight));
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "uHdr"), hdr ? 1.5f : 1.0f);

        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceVbo);
        GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, staging);
        if (instanced) {
            GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 6, n);
        } else {
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, n * 6);
        }
        GL30C.glBindVertexArray(0);
    }

    private static int detectDivisorMode() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps == null) return DIVISOR_NONE;
            if (caps.OpenGL33) return DIVISOR_GL33;
            if (caps.GL_ARB_instanced_arrays) return DIVISOR_ARB;
            return DIVISOR_NONE;
        } catch (RuntimeException ignored) {
            return DIVISOR_NONE;
        }
    }

    private void setAttribDivisor(int index, int divisor) {
        if (divisorMode == DIVISOR_GL33) {
            GL33C.glVertexAttribDivisor(index, divisor);
        } else if (divisorMode == DIVISOR_ARB) {
            ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
        }
    }

    private void ensureResources(int required) {
        if (program == 0) {
            divisorMode = detectDivisorMode();
            int vs = compile(GL20C.GL_VERTEX_SHADER, SPLAT_VERT);
            int fs = compile(GL20C.GL_FRAGMENT_SHADER, SPLAT_FRAG);
            int prog = GL20C.glCreateProgram();
            GL20C.glAttachShader(prog, vs);
            GL20C.glAttachShader(prog, fs);
            GL20C.glBindAttribLocation(prog, 0, "aCenter");
            GL20C.glBindAttribLocation(prog, 1, "aScale");
            GL20C.glBindAttribLocation(prog, 2, "aQuat");
            GL20C.glBindAttribLocation(prog, 3, "aColor");
            GL30C.glBindFragDataLocation(prog, 0, "fragColor");
            GL20C.glLinkProgram(prog);
            GL20C.glDeleteShader(vs);
            GL20C.glDeleteShader(fs);
            if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
                String log = GL20C.glGetProgramInfoLog(prog, 4096);
                GL20C.glDeleteProgram(prog);
                throw new IllegalStateException("Failed to link LumenFX Gaussian Splat shader: " + log);
            }
            program = prog;
        }
        if (required <= capacitySplats && vao != 0) return;
        int nextCap = Math.max(required, Math.max(256, capacitySplats * 2));
        int vertsPerSplat = divisorMode != DIVISOR_NONE ? 1 : 6;
        if (vao == 0) vao = GL30C.glGenVertexArrays();
        if (instanceVbo == 0) instanceVbo = GL15C.glGenBuffers();
        if (staging != null) MemoryUtil.memFree(staging);
        staging = MemoryUtil.memAllocFloat(nextCap * vertsPerSplat * FLOATS_PER_SPLAT);
        capacitySplats = nextCap;

        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceVbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) nextCap * vertsPerSplat * FLOATS_PER_SPLAT * Float.BYTES, GL15C.GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_SPLAT * Float.BYTES;
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0L);
        setAttribDivisor(0, 1);
        GL20C.glEnableVertexAttribArray(1);
        GL20C.glVertexAttribPointer(1, 3, GL11C.GL_FLOAT, false, stride, 3L * Float.BYTES);
        setAttribDivisor(1, 1);
        GL20C.glEnableVertexAttribArray(2);
        GL20C.glVertexAttribPointer(2, 4, GL11C.GL_FLOAT, false, stride, 6L * Float.BYTES);
        setAttribDivisor(2, 1);
        GL20C.glEnableVertexAttribArray(3);
        GL20C.glVertexAttribPointer(3, 4, GL11C.GL_FLOAT, false, stride, 10L * Float.BYTES);
        setAttribDivisor(3, 1);
        GL30C.glBindVertexArray(0);
    }

    private static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(s, 4096);
            GL20C.glDeleteShader(s);
            throw new IllegalStateException("Failed to compile LumenFX Gaussian Splat shader: " + log);
        }
        return s;
    }

    public void dispose() {
        if (program != 0) { GL20C.glDeleteProgram(program); program = 0; }
        if (vao != 0) { GL30C.glDeleteVertexArrays(vao); vao = 0; }
        if (instanceVbo != 0) { GL15C.glDeleteBuffers(instanceVbo); instanceVbo = 0; }
        if (staging != null) { MemoryUtil.memFree(staging); staging = null; }
        capacitySplats = 0;
        MemoryUtil.memFree(matBuf);
    }
}
