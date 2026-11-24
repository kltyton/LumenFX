package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed Niagara histogram, recursive prefix scan, and bitonic scatter passes. */
final class NiagaraGpuNeighborQuerySort implements AutoCloseable {
    private static final int SCAN_THREADS = 128, SCAN_TILE = 512, SLOT_THREADS = 64;
    private record Scratch(GpuBuffer sums, GpuBuffer offsets, int count) {
        void close() { sums.close(); offsets.close(); }
    }
    private final NiagaraGpuPrograms programs;
    private final List<Scratch> scratch = new ArrayList<>();

    NiagaraGpuNeighborQuerySort(NiagaraGpuPrograms programs) { this.programs = programs; }

    void histogram(CommandEncoder encoder, GpuBufferSlice keys, GpuBufferSlice counts, int slots) {
        if (slots == 0) return;
        dispatch(encoder, "histogram", Math.ceilDiv(slots, SLOT_THREADS), SLOT_THREADS,
                Map.of("CellIdBuffer", keys, "CellCountBuffer", counts), Map.of("NumSlots", slots));
    }

    void scatter(CommandEncoder encoder, GpuBufferSlice keys, GpuBufferSlice particleIds, GpuBufferSlice offsets,
                 GpuBufferSlice particles, GpuBufferSlice tags, GpuBufferSlice sortedTags,
                 int slots, int slotsPerParticle, boolean persistent) {
        if (slots == 0) return;
        dispatch(encoder, "scatter", Math.ceilDiv(slots, SLOT_THREADS), SLOT_THREADS, Map.of(
                "CellIdBuffer", keys, "ParticleIdIndexBuffer", particleIds, "CellOffsetBuffer", offsets,
                "ParticleListBuffer", particles, "AcquireTagBuffer", tags, "AcquireTagListBuffer", sortedTags),
                Map.of("NumSlots", slots, "MaxCellsPerParticle", slotsPerParticle, "bHasPersistentIDBuffers", persistent ? 1 : 0));
    }

    void prefix(CommandEncoder encoder, GpuBufferSlice counts, GpuBufferSlice offsets, int cells) {
        scan(encoder, counts, offsets, cells, 0);
    }

    private void scan(CommandEncoder encoder, GpuBufferSlice input, GpuBufferSlice output, int count, int level) {
        int groups = Math.ceilDiv(count, SCAN_TILE);
        var blocks = scratch(level, groups);
        var sizes = Map.of("NumElements", count, "NumGroups", groups);
        dispatch(encoder, "scan", groups, SCAN_THREADS,
                Map.of("InputBuffer", input, "OutputBuffer", output, "BlockSumsBuffer", blocks.sums().slice()), sizes);
        if (groups == 1) return;
        scan(encoder, blocks.sums().slice(), blocks.offsets().slice(), groups, level + 1);
        dispatch(encoder, "propagate", groups, SCAN_THREADS,
                Map.of("BlockSumsBuffer", blocks.offsets().slice(), "OutputBuffer", output), sizes);
    }

    private Scratch scratch(int level, int groups) {
        var old = level < scratch.size() ? scratch.get(level) : null;
        if (old != null && old.count() >= groups) return old;
        var sums = allocate("Niagara.NeighborQuery.BlockSums", groups);
        try {
            var result = new Scratch(sums, allocate("Niagara.NeighborQuery.ScannedBlockSums", groups), groups);
            if (old != null) { scratch.set(level, result); old.close(); }
            else scratch.add(result);
            return result;
        } catch (RuntimeException | Error failure) {
            sums.close();
            throw failure;
        }
    }

    private void dispatch(CommandEncoder encoder, String pass, int groups, int threads, Map<String, GpuBufferSlice> buffers,
                          Map<String, Integer> values) {
        var stage = programs.neighborQuery(pass);
        int maxX = Math.min(65535, programs.runtime().maxWorkGroups(0));
        int x = groups <= maxX ? groups : Math.min(maxX, Math.ceilDiv(groups, (int)Math.ceil(Math.sqrt(groups))));
        int y = Math.ceilDiv(groups, x);
        if (y > programs.runtime().maxWorkGroups(1))
            throw new IllegalArgumentException("Niagara Neighbor Query dispatch exceeds GPU group limits");
        var builder = ComputeBindings.builder();
        for (var binding : stage.shader().bindings()) if (binding instanceof ShaderBinding.StorageBuffer storage)
            builder.storage(storage.name(), Objects.requireNonNull(buffers.get(storage.name()), storage.name()), storage.access());
        stage.dispatch(encoder, builder, name -> {
            String field = name.substring(name.lastIndexOf('.') + 1);
            Integer word = field.equals("GroupsX") ? Integer.valueOf(x) : values.get(field);
            return word == null ? null : ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(word).array();
        }, Math.multiplyExact(x, threads), y, 1);
    }

    static GpuBuffer allocate(String name, int words) {
        return RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST),
                (long)words * Integer.BYTES);
    }

    @Override public void close() {
        scratch.forEach(Scratch::close);
        scratch.clear();
    }
}
