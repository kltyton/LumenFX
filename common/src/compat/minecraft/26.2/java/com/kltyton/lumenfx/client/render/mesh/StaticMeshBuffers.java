package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Material sections partitioned only at the device's actual texel-buffer limit. */
public final class StaticMeshBuffers implements AutoCloseable {
    public static final class Chunk implements MeshDrawChunk, AutoCloseable {
        private final StaticMeshGeometry.Section section;
        private final int firstSourceIndex;
        private final GpuBuffer vertices, indices, info;
        private GpuBuffer reversed;

        private Chunk(StaticMeshGeometry.Section section, int firstSourceIndex, GpuBuffer vertices, GpuBuffer indices, GpuBuffer info) {
            this.section = section;
            this.firstSourceIndex = firstSourceIndex;
            this.vertices = vertices;
            this.indices = indices;
            this.info = info;
        }

        @Override public StaticMeshGeometry.Section section() { return section; }
        @Override public int indexCount() { return (int) (indices.size() / Integer.BYTES); }
        @Override public GpuBuffer vertices() { return vertices; }
        @Override public GpuBuffer indices() { return indices; }
        @Override public GpuBuffer info() { return info; }

        public GpuBuffer reversedIndices(CommandEncoder encoder, StaticMeshGeometry geometry) {
            if (reversed == null) {
                var remap = new Int2IntOpenHashMap();
                remap.defaultReturnValue(-1);
                int[] triangleIndices = new int[indexCount()];
                for (int index = 0; index < triangleIndices.length; index++) {
                    int vertex = geometry.index(firstSourceIndex + index);
                    int next = remap.size();
                    int previous = remap.putIfAbsent(vertex, next);
                    triangleIndices[index] = previous < 0 ? next : previous;
                }
                reversed = uploadIndices(encoder, triangleIndices, true);
            }
            return reversed;
        }

        @Override public void close() {
            if (reversed != null) reversed.close();
            info.close();
            indices.close();
            vertices.close();
        }
    }

    private final List<Chunk> chunks;

    public StaticMeshBuffers(CommandEncoder encoder, StaticMeshGeometry geometry, long maximumTexels) {
        RenderSystem.assertOnRenderThread();
        int maximumVertices = (int)Math.min(Integer.MAX_VALUE, maximumTexels / geometry.vertexTexels());
        long maximumIndices = Math.min(Integer.MAX_VALUE,
                RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize() / Integer.BYTES);
        if (maximumVertices < 3) throw new IllegalArgumentException("Device cannot hold one static mesh triangle");
        List<Chunk> uploaded = new ArrayList<>();
        try {
            for (StaticMeshGeometry.Section section : geometry.sections()) {
                Map<Integer, Integer> remap = new HashMap<>();
                List<Integer> vertices = new ArrayList<>(), indices = new ArrayList<>();
                int end = Math.addExact(section.firstIndex(), section.indexCount());
                int firstSourceIndex = section.firstIndex();
                for (int triangle = section.firstIndex(); triangle < end; triangle += 3) {
                    int additions = 0;
                    for (int corner = 0; corner < 3; corner++) {
                        int vertex = geometry.index(triangle + corner);
                        boolean first = true;
                        for (int previous = 0; previous < corner; previous++)
                            if (geometry.index(triangle + previous) == vertex) first = false;
                        if (first && !remap.containsKey(vertex)) additions++;
                    }
                    if ((long)vertices.size() + additions > maximumVertices || (long)indices.size() + 3 > maximumIndices) {
                        uploaded.add(upload(encoder, geometry, section, firstSourceIndex, vertices, indices));
                        firstSourceIndex = triangle;
                        remap.clear(); vertices.clear(); indices.clear();
                    }
                    for (int corner = 0; corner < 3; corner++) {
                        int vertex = geometry.index(triangle + corner);
                        Integer mapped = remap.get(vertex);
                        if (mapped == null) {
                            mapped = vertices.size();
                            remap.put(vertex, mapped);
                            vertices.add(vertex);
                        }
                        indices.add(mapped);
                    }
                }
                if (!indices.isEmpty()) uploaded.add(upload(encoder, geometry, section, firstSourceIndex, vertices, indices));
            }
            chunks = List.copyOf(uploaded);
        } catch (RuntimeException | Error error) {
            uploaded.forEach(Chunk::close);
            throw error;
        }
    }

    private static Chunk upload(CommandEncoder encoder, StaticMeshGeometry geometry, StaticMeshGeometry.Section section,
                                int firstSourceIndex, List<Integer> sourceVertices, List<Integer> sourceIndices) {
        GpuBuffer vertices = null, indices = null, info = null;
        try {
            long vertexBytes = Math.multiplyExact(Math.multiplyExact((long)sourceVertices.size(), geometry.vertexTexels()), 16);
            vertices = RenderSystem.getDevice().createBuffer(() -> "Niagara static mesh vertices",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, vertexBytes);
            GpuBufferSlice vertexUpload;
            try (var memory = encoder.transientMemory().allocateGpuMapped(vertexBytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
                var data = memory.data().order(ByteOrder.nativeOrder());
                for (int vertex : sourceVertices) geometry.writeVertex(data, vertex);
                vertexUpload = memory.slice();
            }
            encoder.copyToBuffer(vertexUpload, vertices.slice());
            int[] remapped = sourceIndices.stream().mapToInt(Integer::intValue).toArray();
            indices = uploadIndices(encoder, remapped, false);
            info = RenderSystem.getDevice().createBuffer(() -> "Niagara static mesh layout",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
            GpuBufferSlice infoUpload;
            try (var memory = encoder.transientMemory().allocateGpuMapped(16, 4, GpuBuffer.USAGE_COPY_SRC)) {
                memory.data().order(ByteOrder.nativeOrder()).putInt(geometry.vertexTexels())
                        .putInt(geometry.textureCoordinateCount()).putInt(geometry.wideTextureCoordinates() ? 1 : 0).putInt(0);
                infoUpload = memory.slice();
            }
            encoder.copyToBuffer(infoUpload, info.slice());
            return new Chunk(section, firstSourceIndex, vertices, indices, info);
        } catch (RuntimeException | Error error) {
            if (info != null) info.close();
            if (indices != null) indices.close();
            if (vertices != null) vertices.close();
            throw error;
        }
    }

    private static GpuBuffer uploadIndices(CommandEncoder encoder, int[] indices, boolean reverse) {
        long bytes = (long) indices.length * Integer.BYTES;
        var result = RenderSystem.getDevice().createBuffer(() -> reverse ? "Reversed particle mesh indices" : "Niagara static mesh indices",
                GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, bytes);
        try {
            GpuBufferSlice upload;
            try (var memory = encoder.transientMemory().allocateGpuMapped(bytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
                var data = memory.data().order(ByteOrder.nativeOrder());
                for (int triangle = 0; triangle < indices.length; triangle += 3) {
                    data.putInt(indices[triangle]);
                    data.putInt(indices[triangle + (reverse ? 2 : 1)]);
                    data.putInt(indices[triangle + (reverse ? 1 : 2)]);
                }
                upload = memory.slice();
            }
            encoder.copyToBuffer(upload, result.slice());
            return result;
        } catch (RuntimeException | Error failure) {
            result.close();
            throw failure;
        }
    }

    public List<Chunk> chunks() { return chunks; }

    @Override public void close() { chunks.forEach(Chunk::close); }
}
