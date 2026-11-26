package com.kltyton.lumenfx.runtime.gl.groom;

import com.kltyton.lumenfx.runtime.model.GroomAssetModel;
import com.kltyton.lumenfx.runtime.model.GroomAssetModel.Strand;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Native OpenGL 3.3+ Groom hair strand renderer expanding camera-facing strand ribbons with
 * animated wind/gravity sway and Kajiya-Kay dual-lobe specular + root-to-tip ambient occlusion.
 */
public final class DirectGlGroomRenderer {
    private static final int FLOATS_PER_VERTEX = 12; // pos(3), tangent(3), color(3), uvT(2), roughness(1)
    private static final int MAX_VERTICES = 65536;

    private static final String GROOM_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aTangent;
            layout(location = 2) in vec3 aColor;
            layout(location = 3) in vec2 aUv;
            layout(location = 4) in float aRoughness;
            uniform mat4 uViewProj;
            out vec3 vTangent;
            out vec3 vViewPos;
            out vec3 vColor;
            out vec2 vUv;
            out float vRoughness;
            void main() {
                vTangent = aTangent;
                vViewPos = -aPos;
                vColor = aColor;
                vUv = aUv;
                vRoughness = aRoughness;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
            """;

    private static final String GROOM_FRAG = """
            #version 330 core
            in vec3 vTangent;
            in vec3 vViewPos;
            in vec3 vColor;
            in vec2 vUv;
            in float vRoughness;
            out vec4 fragColor;
            float strandSpec(vec3 T, vec3 V, vec3 L, float exponent) {
                vec3 H = normalize(L + V);
                float dotTH = dot(T, H);
                float sinTH = sqrt(max(0.0, 1.0 - dotTH * dotTH));
                float dirAtten = smoothstep(-1.0, 0.0, dotTH);
                return dirAtten * pow(sinTH, exponent);
            }
            void main() {
                vec3 T = length(vTangent) > 0.0001 ? normalize(vTangent) : vec3(0.0, -1.0, 0.0);
                vec3 V = length(vViewPos) > 0.0001 ? normalize(vViewPos) : vec3(0.0, 0.0, 1.0);
                vec3 L = normalize(vec3(0.35, 0.85, 0.40));
                float diffuse = sqrt(max(0.0, 1.0 - dot(T, L) * dot(T, L)));
                float rootAo = mix(0.45, 1.0, vUv.x);
                float exp1 = mix(96.0, 14.0, clamp(vRoughness, 0.05, 1.0));
                float specPrimary = strandSpec(T, V, L, exp1) * 0.45;
                float specSecondary = strandSpec(T, V, L, exp1 * 0.4) * 0.25;
                vec3 col = vColor * (0.35 + 0.65 * diffuse) * rootAo
                         + vec3(1.0) * specPrimary + vColor * specSecondary;
                float edgeAlpha = 1.0 - abs(vUv.y * 2.0 - 1.0);
                float alpha = smoothstep(0.0, 0.25, edgeAlpha);
                if (alpha <= 0.01) discard;
                fragColor = vec4(col, alpha);
            }
            """;

    private int program;
    private int vao;
    private int vbo;
    private FloatBuffer buf;
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);

    public void renderGroom(GroomAssetModel groom, double originX, double originY, double originZ,
                            float scale, float timeSeconds, Matrix4f viewProj, Vector3f camForward, Vector3f camRight,
                            double camX, double camY, double camZ) {
        if (groom.strands().isEmpty()) return;
        ensureResources();
        buf.clear();
        int verts = 0;
        float ox = (float) (originX - camX);
        float oy = (float) (originY - camY);
        float oz = (float) (originZ - camZ);
        float wind = groom.windStrength();

        for (int sIdx = 0; sIdx < groom.strands().size(); sIdx++) {
            Strand s = groom.strands().get(sIdx);
            int pts = s.pointCount();
            if (pts < 2) continue;
            float[] rp = s.restPoints();
            float swayPhase = timeSeconds * 2.4f + sIdx * 0.37f;
            float swayAmp = wind * (1.0f - s.stiffness() * 0.7f);
            for (int i = 0; i < pts - 1 && verts + 6 <= MAX_VERTICES; i++) {
                float t0 = (float) i / (pts - 1);
                float t1 = (float) (i + 1) / (pts - 1);
                float bend0 = t0 * t0 * swayAmp;
                float bend1 = t1 * t1 * swayAmp;
                float ax = ox + rp[i * 3] * scale + (float) Math.sin(swayPhase) * bend0;
                float ay = oy + rp[i * 3 + 1] * scale - t0 * t0 * groom.gravity() * 0.01f * (1.0f - s.stiffness());
                float az = oz + rp[i * 3 + 2] * scale + (float) Math.cos(swayPhase * 0.8f) * bend0;
                float bx = ox + rp[(i + 1) * 3] * scale + (float) Math.sin(swayPhase) * bend1;
                float by = oy + rp[(i + 1) * 3 + 1] * scale - t1 * t1 * groom.gravity() * 0.01f * (1.0f - s.stiffness());
                float bz = oz + rp[(i + 1) * 3 + 2] * scale + (float) Math.cos(swayPhase * 0.8f) * bend1;

                float tx = bx - ax, ty = by - ay, tz = bz - az;
                float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (tl > 1e-4f) { tx /= tl; ty /= tl; tz /= tl; }
                else { tx = 0f; ty = -1f; tz = 0f; }

                float sx = ty * camForward.z - tz * camForward.y;
                float sy = tz * camForward.x - tx * camForward.z;
                float sz = tx * camForward.y - ty * camForward.x;
                float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl > 1e-4f) { sx /= sl; sy /= sl; sz /= sl; }
                else { sx = camRight.x; sy = camRight.y; sz = camRight.z; }

                float wa = (s.rootWidth() + (s.tipWidth() - s.rootWidth()) * t0) * 0.5f * scale;
                float wb = (s.rootWidth() + (s.tipWidth() - s.rootWidth()) * t1) * 0.5f * scale;
                put(ax - sx * wa, ay - sy * wa, az - sz * wa, tx, ty, tz, s.r(), s.g(), s.b(), t0, 0f, s.roughness());
                put(ax + sx * wa, ay + sy * wa, az + sz * wa, tx, ty, tz, s.r(), s.g(), s.b(), t0, 1f, s.roughness());
                put(bx + sx * wb, by + sy * wb, bz + sz * wb, tx, ty, tz, s.r(), s.g(), s.b(), t1, 1f, s.roughness());
                put(ax - sx * wa, ay - sy * wa, az - sz * wa, tx, ty, tz, s.r(), s.g(), s.b(), t0, 0f, s.roughness());
                put(bx + sx * wb, by + sy * wb, bz + sz * wb, tx, ty, tz, s.r(), s.g(), s.b(), t1, 1f, s.roughness());
                put(bx - sx * wb, by - sy * wb, bz - sz * wb, tx, ty, tz, s.r(), s.g(), s.b(), t1, 0f, s.roughness());
                verts += 6;
            }
        }
        if (verts <= 0) return;

        buf.flip();
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(true);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        GL20C.glUseProgram(program);
        matBuf.clear(); viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "uViewProj"), false, matBuf);
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
        GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, buf);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, verts);
        GL30C.glBindVertexArray(0);
    }

    private void put(float x, float y, float z, float tx, float ty, float tz,
                     float r, float g, float b, float u, float v, float rough) {
        buf.put(x).put(y).put(z).put(tx).put(ty).put(tz).put(r).put(g).put(b).put(u).put(v).put(rough);
    }

    private void ensureResources() {
        if (program != 0) return;
        int vs = compile(GL20C.GL_VERTEX_SHADER, GROOM_VERT);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, GROOM_FRAG);
        program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);

        vao = GL30C.glGenVertexArrays();
        vbo = GL15C.glGenBuffers();
        buf = MemoryUtil.memAllocFloat(MAX_VERTICES * FLOATS_PER_VERTEX);
        GL30C.glBindVertexArray(vao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
        GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) MAX_VERTICES * FLOATS_PER_VERTEX * Float.BYTES, GL15C.GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        GL20C.glEnableVertexAttribArray(0);
        GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0L);
        GL20C.glEnableVertexAttribArray(1);
        GL20C.glVertexAttribPointer(1, 3, GL11C.GL_FLOAT, false, stride, 3L * Float.BYTES);
        GL20C.glEnableVertexAttribArray(2);
        GL20C.glVertexAttribPointer(2, 3, GL11C.GL_FLOAT, false, stride, 6L * Float.BYTES);
        GL20C.glEnableVertexAttribArray(3);
        GL20C.glVertexAttribPointer(3, 2, GL11C.GL_FLOAT, false, stride, 9L * Float.BYTES);
        GL20C.glEnableVertexAttribArray(4);
        GL20C.glVertexAttribPointer(4, 1, GL11C.GL_FLOAT, false, stride, 11L * Float.BYTES);
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
        if (vbo != 0) { GL15C.glDeleteBuffers(vbo); vbo = 0; }
        if (buf != null) { MemoryUtil.memFree(buf); buf = null; }
        MemoryUtil.memFree(matBuf);
    }
}
