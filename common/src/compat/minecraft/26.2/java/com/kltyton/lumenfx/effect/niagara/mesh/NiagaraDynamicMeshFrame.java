package com.kltyton.lumenfx.effect.niagara.mesh;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import java.util.List;

/** Layout and raw words of one published Niagara dynamic mesh. Attribute offsets are in words. */
public record NiagaraDynamicMeshFrame(long revision, int triangles, int vertices, int uvChannels, int tangentOffset, int uvOffset,
                                      int colorOffset, int vertexWords, NiagaraBounds bounds, List<Section> sections,
                                      int[] indices, int[] attributes) {
    public record Section(int triangleOffset, int maxTriangles, int allocatedTriangles, int materialIndex) { }

    public NiagaraDynamicMeshFrame {
        sections = List.copyOf(sections);
        indices = indices.clone(); attributes = attributes.clone();
        if (triangles < 0 || vertices < 0 || uvChannels < 0 || vertexWords < 0
                || indices.length != 0 && indices.length != (long)triangles * 3
                || attributes.length != 0 && attributes.length != vertexWords)
            throw new IllegalArgumentException("Invalid Niagara dynamic mesh buffers");
    }

    @Override public int[] indices() { return indices.clone(); }
    @Override public int[] attributes() { return attributes.clone(); }
}
