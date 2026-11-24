package com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic;

import com.kltyton.lumenfx.client.render.mesh.MeshDrawChunk;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.effect.render.mesh.MeshSectionGeometry;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.Section;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry.SectionInfo;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Adapts the live mesh buffers to the ordinary section draws without a geometry readback. */
public final class NiagaraDynamicDrawGeometry implements MeshSectionGeometry, AutoCloseable {
    private record Chunk(Section section, int indexCount, GpuBuffer vertices, GpuBuffer indices, GpuBuffer info) implements MeshDrawChunk { }
    private final NiagaraDynamicMeshFrame frame;
    private final List<Section> sections;
    private final GpuBuffer sourceIndices;
    private final GpuBuffer info;
    private final List<Chunk> chunks;

    NiagaraDynamicDrawGeometry(CommandEncoder encoder, NiagaraDynamicMeshFrame frame, boolean gpuAllocation, GpuBuffer sourceIndices,
                               GpuBuffer vertices, long maxTexels) {
        if (frame.vertexWords() > maxTexels) throw new IllegalArgumentException("Niagara dynamic mesh exceeds the vertex texel-buffer limit");
        this.frame = frame;
        this.sourceIndices = sourceIndices;
        var ranges = new ArrayList<Section>();
        var draws = new ArrayList<Chunk>();
        GpuBuffer layout = RenderSystem.getDevice().createBuffer(() -> "Niagara dynamic mesh draw layout",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        try {
            GpuBufferSlice upload;
            try (var memory = encoder.transientMemory().allocateGpuMapped(16, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                memory.data().order(ByteOrder.nativeOrder()).putInt(frame.tangentOffset()).putInt(frame.uvOffset())
                        .putInt(frame.colorOffset()).putInt(frame.uvChannels());
                upload = memory.slice();
            }
            encoder.copyToBuffer(upload, layout.slice());
            for (int index = 0; index < frame.sections().size(); index++) {
                var section = frame.sections().get(index);
                int count = Math.multiplyExact(gpuAllocation ? section.maxTriangles() : section.allocatedTriangles(), 3);
                if (frame.vertices() == 0) count = 0;
                var range = new Section(index, Integer.toString(section.materialIndex()), Math.multiplyExact(section.triangleOffset(), 3),
                        count, new SectionInfo(section.materialIndex(), false, false, false, false, false));
                ranges.add(range);
                if (count == 0) continue;
                var indices = RenderSystem.getDevice().createBuffer(() -> "Niagara dynamic mesh draw indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, (long)count * Integer.BYTES);
                draws.add(new Chunk(range, count, vertices, indices, layout));
            }
        } catch (RuntimeException | Error failure) {
            draws.forEach(chunk -> chunk.indices().close());
            layout.close();
            throw failure;
        }
        sections = List.copyOf(ranges);
        chunks = List.copyOf(draws);
        info = layout;
    }

    public NiagaraDynamicMeshFrame frame() { return frame; }
    @Override public List<Section> sections() { return sections; }

    public List<? extends MeshDrawChunk> prepare(CommandEncoder encoder) {
        for (var chunk : chunks)
            encoder.copyToBuffer(sourceIndices.slice((long)chunk.section().firstIndex() * Integer.BYTES,
                    (long)chunk.indexCount() * Integer.BYTES), chunk.indices().slice());
        return chunks;
    }

    @Override public void close() {
        try { chunks.forEach(chunk -> chunk.indices().close()); }
        finally { info.close(); }
    }
}
