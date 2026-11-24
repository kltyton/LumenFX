package com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Owns the live index, vertex and section buffers of each dynamic mesh interface in a Niagara system. */
public final class NiagaraGpuDynamicMeshes implements AutoCloseable {
    public static final class Mesh implements AutoCloseable {
        private final NiagaraDynamicMeshFrame layout;
        private final GpuBuffer sectionDefaults, sections, indices, vertices;
        private @Nullable NiagaraDynamicDrawGeometry drawGeometry;
        private boolean drawGpuAllocation;
        Mesh(NiagaraDynamicMeshFrame layout, GpuBuffer sectionDefaults, GpuBuffer sections, GpuBuffer indices, GpuBuffer vertices) {
            this.layout = layout; this.sectionDefaults = sectionDefaults; this.sections = sections;
            this.indices = indices; this.vertices = vertices;
        }
        public NiagaraDynamicMeshFrame layout() { return layout; }
        public GpuBuffer sections() { return sections; }
        public GpuBuffer indices() { return indices; }
        public GpuBuffer vertices() { return vertices; }
        void beginTick(CommandEncoder encoder) { encoder.copyToBuffer(sectionDefaults.slice(), sections.slice()); }
        NiagaraDynamicDrawGeometry drawGeometry(CommandEncoder encoder, boolean gpuAllocation, long maxTexels) {
            if (drawGeometry == null || drawGpuAllocation != gpuAllocation) {
                var next = new NiagaraDynamicDrawGeometry(encoder, layout, gpuAllocation, indices, vertices, maxTexels);
                if (drawGeometry != null) drawGeometry.close();
                drawGeometry = next;
                drawGpuAllocation = gpuAllocation;
            }
            return drawGeometry;
        }
        @Override public void close() {
            try (sectionDefaults; sections; indices; vertices) { if (drawGeometry != null) drawGeometry.close(); }
        }
    }
    private final NiagaraGpuPrograms programs;
    private final Map<String, Mesh> objects = new LinkedHashMap<>();

    public NiagaraGpuDynamicMeshes(NiagaraGpuPrograms programs) { this.programs = programs; }

    public void prepare(CommandEncoder encoder, String id, NiagaraDynamicMeshFrame publication) {
        update(encoder, id, publication).beginTick(encoder);
    }

    public NiagaraDynamicDrawGeometry drawGeometry(CommandEncoder encoder, String id, NiagaraDynamicMeshFrame publication, boolean gpuAllocation) {
        return update(encoder, id, publication).drawGeometry(encoder, gpuAllocation, programs.runtime().maxTexelBufferElements());
    }

    private Mesh update(CommandEncoder encoder, String id, NiagaraDynamicMeshFrame publication) {
        var mesh = objects.get(id);
        if (mesh == null || mesh.layout().revision() != publication.revision()) {
            mesh = create(encoder, id, publication);
            var old = objects.put(id, mesh);
            if (old != null) old.close();
        }
        return mesh;
    }

    public Mesh mesh(String id) { return Objects.requireNonNull(objects.get(id), "Missing prepared Niagara dynamic mesh " + id); }

    public CompletableFuture<@Nullable NiagaraDynamicMeshFrame> captureCache(CommandEncoder encoder, @Nullable ClientLevel world,
                                                                               String id, NiagaraDynamicMeshFrame cpu) {
        var mesh = objects.get(id);
        if (mesh == null || mesh.layout().triangles() == 0 || mesh.layout().vertexWords() == 0)
            return CompletableFuture.completedFuture(null);
        var layout = mesh.layout();
        if (layout.triangles() != cpu.triangles() || layout.vertices() != cpu.vertices() || layout.uvChannels() != cpu.uvChannels()
                || layout.vertexWords() != cpu.vertexWords() || layout.sections().size() != cpu.sections().size()
                || layout.tangentOffset() != cpu.tangentOffset() || layout.uvOffset() != cpu.uvOffset()
                || layout.colorOffset() != cpu.colorOffset()) return CompletableFuture.completedFuture(null);
        var sections = NiagaraGpuCacheReadbacks.array(encoder, world, id + ".Sections", null,
                mesh.sections().slice(), 2, layout.sections().size());
        var indices = NiagaraGpuCacheReadbacks.array(encoder, world, id + ".Indices", null,
                mesh.indices().slice(), 3, layout.triangles());
        var vertices = NiagaraGpuCacheReadbacks.array(encoder, world, id + ".Vertices", null,
                mesh.vertices().slice(), 1, layout.vertexWords());
        return CompletableFuture.allOf(sections, indices, vertices).thenApply(ignored -> {
            int[] sectionWords = sections.join().words();
            var descriptors = new ArrayList<NiagaraDynamicMeshFrame.Section>(layout.sections().size());
            for (int index = 0; index < layout.sections().size(); index++) {
                var section = layout.sections().get(index);
                descriptors.add(new NiagaraDynamicMeshFrame.Section(section.triangleOffset(), section.maxTriangles(),
                        sectionWords[index * 2 + 1], section.materialIndex()));
            }
            return new NiagaraDynamicMeshFrame(layout.revision(), layout.triangles(), layout.vertices(), layout.uvChannels(),
                    layout.tangentOffset(), layout.uvOffset(), layout.colorOffset(), layout.vertexWords(), layout.bounds(),
                    descriptors, indices.join().words(), vertices.join().words());
        });
    }

    private Mesh create(CommandEncoder encoder, String id, NiagaraDynamicMeshFrame publication) {
        GpuBuffer defaults = null, sections = null, indices = null, vertices = null;
        try {
            int[] sectionWords = new int[Math.max(1, Math.multiplyExact(publication.sections().size(), 2))];
            for (int index = 0; index < publication.sections().size(); index++) {
                var section = publication.sections().get(index);
                sectionWords[index * 2] = section.maxTriangles();
                sectionWords[index * 2 + 1] = section.allocatedTriangles();
            }
            defaults = buffer(id + ".SectionDefaults", sectionWords.length, 0);
            sections = buffer(id + ".Sections", sectionWords.length, 0);
            indices = buffer(id + ".Indices", Math.multiplyExact(publication.triangles(), 3), GpuBuffer.USAGE_INDEX);
            vertices = buffer(id + ".Vertices", publication.vertexWords(), GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER);
            upload(encoder, defaults, sectionWords);
            upload(encoder, indices, publication.indices());
            upload(encoder, vertices, publication.attributes());
            return new Mesh(publication, defaults, sections, indices, vertices);
        } catch (RuntimeException | Error failure) {
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
            if (sections != null) sections.close();
            if (defaults != null) defaults.close();
            throw failure;
        }
    }

    private GpuBuffer buffer(String label, int words, int usage) {
        long size = (long)Math.max(1, words) * Integer.BYTES;
        if (size > programs.runtime().maxStorageBufferBytes()) throw new IllegalArgumentException("Dynamic mesh exceeds GPU buffer capacity: " + label);
        return RenderSystem.getDevice().createBuffer(() -> label, ComputeUsage.storageBuffer(usage | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), size);
    }

    private void upload(CommandEncoder encoder, GpuBuffer target, int[] words) {
        if (words.length == 0) {
            programs.runtime().clearBuffer(target.slice(), 0);
            return;
        }
        GpuBufferSlice staging;
        try (var mapped = encoder.transientMemory().allocateGpuMapped((long)words.length * Integer.BYTES,
                Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().put(words);
            staging = mapped.slice();
        }
        encoder.copyToBuffer(staging, target.slice());
    }

    @Override public void close() {
        objects.values().forEach(Mesh::close);
        objects.clear();
    }
}
