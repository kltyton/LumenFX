package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.runtime.model.StaticMeshModel;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Uploads decoded {@link StaticMeshModel} geometry ({@code .lfmesh} v1-3 and Minecraft JSON block models)
 * into real indexed OpenGL VAOs/VBOs/EBOs.
 */
public final class DirectGlMeshCache {
    public record GpuMesh(int vao, int vbo, int ebo, int indexCount, float unitScale) { }

    private final Map<StaticMeshModel, GpuMesh> uploaded = new IdentityHashMap<>();

    public GpuMesh resolve(StaticMeshModel model) {
        GpuMesh existing = uploaded.get(model);
        if (existing != null) return existing;
        GpuMesh created = upload(model);
        uploaded.put(model, created);
        return created;
    }

    private static GpuMesh upload(StaticMeshModel model) {
        int vc = model.vertexCount();
        int ic = model.indexCount();
        float[] pos = model.positions();
        float[] norm = model.normals();
        float[] uv = model.uvs();
        float[] col = model.colors();
        int[] indices = model.indices();

        if (vc <= 0 || ic <= 0) {
            return new GpuMesh(0, 0, 0, 0, model.unitScale());
        }
        int vao = GL30C.glGenVertexArrays();
        int vbo = GL15C.glGenBuffers();
        int ebo = GL15C.glGenBuffers();

        GL30C.glBindVertexArray(vao);
        FloatBuffer vbuf = MemoryUtil.memAllocFloat(vc * 12);
        IntBuffer ibuf = MemoryUtil.memAllocInt(ic);
        try {
            for (int v = 0; v < vc; v++) {
                vbuf.put(pos[v * 3]).put(pos[v * 3 + 1]).put(pos[v * 3 + 2]);
                vbuf.put(norm[v * 3]).put(norm[v * 3 + 1]).put(norm[v * 3 + 2]);
                vbuf.put(uv[v * 2]).put(uv[v * 2 + 1]);
                vbuf.put(col[v * 4]).put(col[v * 4 + 1]).put(col[v * 4 + 2]).put(col[v * 4 + 3]);
            }
            vbuf.flip();
            for (int i = 0; i < ic; i++) {
                int idx = indices[i];
                ibuf.put(idx >= 0 && idx < vc ? idx : 0);
            }
            ibuf.flip();

            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vbuf, GL15C.GL_STATIC_DRAW);

            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, ibuf, GL15C.GL_STATIC_DRAW);

            int stride = 12 * Float.BYTES;
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 3, GL11C.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 2, GL11C.GL_FLOAT, false, stride, 6L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(3);
            GL20C.glVertexAttribPointer(3, 4, GL11C.GL_FLOAT, false, stride, 8L * Float.BYTES);
        } finally {
            MemoryUtil.memFree(vbuf);
            MemoryUtil.memFree(ibuf);
            GL30C.glBindVertexArray(0);
        }
        return new GpuMesh(vao, vbo, ebo, ic, model.unitScale());
    }

    public void clear() {
        for (GpuMesh m : uploaded.values()) {
            GL30C.glDeleteVertexArrays(m.vao());
            GL15C.glDeleteBuffers(m.vbo());
            GL15C.glDeleteBuffers(m.ebo());
        }
        uploaded.clear();
    }
}
