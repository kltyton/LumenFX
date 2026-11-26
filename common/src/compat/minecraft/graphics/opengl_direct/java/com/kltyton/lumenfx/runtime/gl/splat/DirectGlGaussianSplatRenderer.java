package com.kltyton.lumenfx.runtime.gl.splat;

import com.kltyton.lumenfx.runtime.model.GaussianSplatModel.Splat;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/**
 * Native OpenGL 3.3+ 3D Gaussian Splatting renderer projecting 3D anisotropic ellipsoids
 * ({@code Sigma = R * S * S^T * R^T}) into screen-space 2D Gaussian eigenvectors with
 * back-to-front depth sorting and exact {@code exp(-0.5 * r^2)} falloff.
 */
public final class DirectGlGaussianSplatRenderer {
    private static final int FLOATS_PER_SPLAT = 14; // pos(3), scale(3), quat(4), rgba(4)

    private static final String SPLAT_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aCenter;
            layout(location = 1) in vec3 aScale;
            layout(location = 2) in vec4 aQuat;
            layout(location = 3) in vec4 aColor;
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

                vec2 corner = CORNERS[gl_VertexID];
                vec2 pxOffset = majorDir * (corner.x * rad1) + minorDir * (corner.y * rad2);
                vec2 ndcOffset = (pxOffset * 2.0) / max(uViewport, vec2(1.0));
                gl_Position = vec4(ndc.xy + ndcOffset, ndc.z, 1.0);
                vConicOffset = corner * 3.0;
                vColor = aColor;
            }
            """;

    private static final String SPLAT_FRAG = """
            #version 330 core
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

    private int program;
    private int vao;
    private int instanceVbo;
    private int capacitySplats;
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

        staging.clear();
        for (int i = 0; i < n; i++) {
            Splat s = splats.get((int) sortPairs[i]);
            staging.put(ox + s.x() * scale).put(oy + s.y() * scale).put(oz + s.z() * scale);
            staging.put(Math.max(0.005f, s.sx() * scale)).put(Math.max(0.005f, s.sy() * scale)).put(Math.max(0.005f, s.sz() * scale));
            staging.put(s.qx()).put(s.qy()).put(s.qz()).put(s.qw());
            staging.put(s.r() * tintR).put(s.g() * tintG).put(s.b() * tintB).put(s.opacity() * tintA);
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
        GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 6, n);
        GL30C.glBindVertexArray(0);
    }

    private void ensureResources(int required) {
        if (program == 0) {
            int vs = compile(GL20C.GL_VERTEX_SHADER, SPLAT_VERT);
            int fs = compile(GL20C.GL_FRAGMENT_SHADER, SPLAT_FRAG);
            program = GL20C.glCreateProgram();
            GL20C.glAttachShader(program, vs);
            GL20C.glAttachShader(program, fs);
            GL20C.glLinkProgram(program);
            GL20C.glDeleteShader(vs);
            GL20C.glDeleteShader(fs);
        }
        if (required <= capacitySplats && vao != 0) return;
        int nextCap = Math.max(required, Math.max(256, capacitySplats * 2));
        if (vao == 0) vao = GL30C.glGenVertexArrays();
        if (instanceVbo == 0) instanceVbo = GL15C.glGenBuffers();
        if (staging != null) MemoryUtil.memFree(staging);
        staging = MemoryUtil.memAllocFloat(nextCap * FLOATS_PER_SPLAT);
        capacitySplats = nextCap;

        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, instanceVbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) nextCap * FLOATS_PER_SPLAT * Float.BYTES, GL15C.GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_SPLAT * Float.BYTES;
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0L);
        GL33C.glVertexAttribDivisor(0, 1);
        GL20C.glEnableVertexAttribArray(1);
        GL20C.glVertexAttribPointer(1, 3, GL11C.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL33C.glVertexAttribDivisor(1, 1);
        GL20C.glEnableVertexAttribArray(2);
        GL20C.glVertexAttribPointer(2, 4, GL11C.GL_FLOAT, false, stride, 6L * Float.BYTES);
        GL33C.glVertexAttribDivisor(2, 1);
        GL20C.glEnableVertexAttribArray(3);
        GL20C.glVertexAttribPointer(3, 4, GL11C.GL_FLOAT, false, stride, 10L * Float.BYTES);
        GL33C.glVertexAttribDivisor(3, 1);
        GL30C.glBindVertexArray(0);
    }

    private static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
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
