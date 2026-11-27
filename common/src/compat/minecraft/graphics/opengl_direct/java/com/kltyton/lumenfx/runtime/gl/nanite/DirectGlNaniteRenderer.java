package com.kltyton.lumenfx.runtime.gl.nanite;

import com.kltyton.lumenfx.runtime.gl.DirectGlMeshCache;
import com.kltyton.lumenfx.runtime.gl.DirectGlMeshCache.GpuMesh;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import com.kltyton.lumenfx.runtime.model.NaniteClusterModel;
import com.kltyton.lumenfx.runtime.model.NaniteClusterModel.Cluster;
import com.kltyton.lumenfx.runtime.model.StaticMeshModel;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Native OpenGL 3.3+ Nanite cluster/meshlet renderer performing per-cluster frustum culling,
 * screen-space LOD selection, and PBR normal/roughness/emissive shading.
 */
public final class DirectGlNaniteRenderer {
    private static final String NANITE_VERT = """
            #version 150 core
            in vec3 aPos;
            in vec3 aNormal;
            in vec2 aUv;
            in vec4 aColor;
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            out vec3 vNormal;
            out vec3 vViewPos;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                vec4 worldRel = uModel * vec4(aPos, 1.0);
                vNormal = mat3(uModel) * aNormal;
                vViewPos = -worldRel.xyz;
                vUv = aUv;
                vColor = aColor;
                gl_Position = uViewProj * worldRel;
            }
            """;

    private static final String NANITE_FRAG = """
            #version 150 core
            in vec3 vNormal;
            in vec3 vViewPos;
            in vec2 vUv;
            in vec4 vColor;
            uniform sampler2D uTex;
            uniform vec4 uTint;
            uniform float uHdr;
            uniform float uRoughness;
            out vec4 fragColor;
            void main() {
                vec3 N = length(vNormal) > 0.0001 ? normalize(vNormal) : vec3(0.0, 1.0, 0.0);
                vec3 V = length(vViewPos) > 0.0001 ? normalize(vViewPos) : vec3(0.0, 0.0, 1.0);
                vec3 L = normalize(vec3(0.38, 0.84, 0.39));
                vec3 H = normalize(L + V);
                float ndl = max(dot(N, L), 0.0);
                float shininess = mix(96.0, 6.0, clamp(uRoughness, 0.05, 1.0));
                float spec = pow(max(dot(N, H), 0.0), shininess) * mix(0.45, 0.06, clamp(uRoughness, 0.0, 1.0));
                vec4 albedo = texture(uTex, vUv) * vColor * uTint;
                if (albedo.a <= 0.002) discard;
                vec3 lit = albedo.rgb * (0.42 + 0.58 * ndl) + vec3(spec);
                fragColor = vec4(lit * uHdr, albedo.a);
            }
            """;

    private final Map<StaticMeshModel, NaniteClusterModel> clusterCache = new IdentityHashMap<>();
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);
    private int program;

    public NaniteClusterModel resolveClusters(StaticMeshModel mesh) {
        return clusterCache.computeIfAbsent(mesh, NaniteClusterModel::buildFromMesh);
    }

    public int drawInstance(StaticMeshModel mesh, MaterialModel mat, Matrix4f viewProj, Matrix4f modelMat,
                            float camRelX, float camRelY, float camRelZ, float scale,
                            int vpHeight, int textureId, DirectGlMeshCache meshCache,
                            float tintR, float tintG, float tintB, float tintA) {
        GpuMesh gpu = meshCache.resolve(mesh);
        if (gpu.vao() == 0 || gpu.indexCount() <= 0) return 0;
        ensureProgram();
        NaniteClusterModel clusterModel = resolveClusters(mesh);
        List<Cluster> visible = clusterModel.selectVisibleClusters(
                viewProj, modelMat, camRelX, camRelY, camRelZ, scale, Math.max(1, vpHeight), 1.5f);
        if (visible.isEmpty()) return 0;

        GL20C.glUseProgram(program);
        matBuf.clear();
        viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "uViewProj"), false, matBuf);
        matBuf.clear();
        modelMat.get(matBuf);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(program, "uModel"), false, matBuf);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(program, "uTex"), 0);
        GL20C.glUniform4f(GL20C.glGetUniformLocation(program, "uTint"), tintR, tintG, tintB, tintA);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "uHdr"), mat.hdr() ? Math.max(1.5f, mat.emissiveScale()) : 1.0f);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program, "uRoughness"), mat.parameters()[0] > 0f ? mat.parameters()[0] : 0.45f);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textureId);

        GL30C.glBindVertexArray(gpu.vao());
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo());
        for (Cluster c : visible) {
            GL11C.glDrawElements(GL11C.GL_TRIANGLES, c.indexCount(), GL11C.GL_UNSIGNED_INT,
                    (long) c.firstIndex() * Integer.BYTES);
        }
        GL30C.glBindVertexArray(0);
        return visible.size();
    }

    private void ensureProgram() {
        if (program != 0) return;
        int vs = compile(GL20C.GL_VERTEX_SHADER, NANITE_VERT);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, NANITE_FRAG);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glBindAttribLocation(prog, 0, "aPos");
        GL20C.glBindAttribLocation(prog, 1, "aNormal");
        GL20C.glBindAttribLocation(prog, 2, "aUv");
        GL20C.glBindAttribLocation(prog, 3, "aColor");
        GL30C.glBindFragDataLocation(prog, 0, "fragColor");
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog, 4096);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("Failed to link LumenFX Nanite shader: " + log);
        }
        program = prog;
    }

    private static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(s, 4096);
            GL20C.glDeleteShader(s);
            throw new IllegalStateException("Failed to compile LumenFX Nanite shader: " + log);
        }
        return s;
    }

    public void clear() {
        clusterCache.clear();
    }

    public void dispose() {
        clear();
        if (program != 0) { GL20C.glDeleteProgram(program); program = 0; }
        MemoryUtil.memFree(matBuf);
    }
}
