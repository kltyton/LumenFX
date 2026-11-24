package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Groups resident triangles by the native material slot and raster path selected for their cluster. */
final class NaniteRasterBins implements AutoCloseable {
    static final int MATERIAL_SLOTS = 64;
    private static final int BIN_COUNT = 2 * MATERIAL_SLOTS;
    private static final int CLUSTER_WORKGROUP = 64;
    private static final int TRIANGLES_PER_CLUSTER = 128;
    private static final int COMMAND_BYTES = 4 * Integer.BYTES;

    record Output(GpuBufferSlice triangles, GpuBufferSlice offsets, GpuBufferSlice commands, GpuBufferSlice overflow,
                  int triangleCapacity) {
        GpuBufferSlice hardwareCommand() {
            return commands.buffer().slice(commands.offset() + (long)BIN_COUNT * COMMAND_BYTES, COMMAND_BYTES);
        }

        GpuBufferSlice command(int materialSlot, boolean hardware) {
            if (materialSlot < 0 || materialSlot >= MATERIAL_SLOTS)
                throw new IllegalArgumentException("Invalid Nanite material slot " + materialSlot);
            int bin = hardware ? MATERIAL_SLOTS + materialSlot : materialSlot;
            return commands.buffer().slice(commands.offset() + (long)bin * COMMAND_BYTES, COMMAND_BYTES);
        }
    }

    private final ComputeRuntime runtime;
    private final NaniteComputeProgram count, allocate, scatter;

    NaniteRasterBins(ComputeRuntime runtime, PreparedNiagaraNaniteShaders source) {
        RenderSystem.assertOnRenderThread();
        this.runtime = runtime;
        count = new NaniteComputeProgram(runtime, source.rasterCount(), CLUSTER_WORKGROUP);
        NaniteComputeProgram allocation = null;
        try {
            allocation = new NaniteComputeProgram(runtime, source.rasterAllocate(), BIN_COUNT);
            scatter = new NaniteComputeProgram(runtime, source.rasterScatter(), CLUSTER_WORKGROUP);
            allocate = allocation;
        } catch (RuntimeException | Error failure) {
            if (allocation != null) allocation.close();
            count.close();
            throw failure;
        }
    }

    Output prepare(CommandEncoder encoder, NaniteGpuPages pages, NaniteHierarchyCulling.Output visible, int triangleCapacity) {
        RenderSystem.assertOnRenderThread();
        if (triangleCapacity < 1 || triangleCapacity > Integer.MAX_VALUE / 3
                || visible.clusterCapacity() > Integer.MAX_VALUE / TRIANGLES_PER_CLUSTER)
            throw new IllegalArgumentException("Invalid Nanite raster triangle capacity");
        int columns = Math.min(visible.clusterCapacity(), runtime.maxWorkGroups(0));
        int rows = Math.ceilDiv(visible.clusterCapacity(), columns);
        if (rows > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Nanite cluster count exceeds raster dispatch capacity");

        var counts = buffer(encoder, BIN_COUNT * Integer.BYTES, GpuBuffer.USAGE_COPY_DST);
        var offsets = buffer(encoder, BIN_COUNT * Integer.BYTES, 0);
        var cursors = buffer(encoder, BIN_COUNT * Integer.BYTES, 0);
        var triangles = buffer(encoder, (long)triangleCapacity * 2 * Integer.BYTES, 0);
        var commands = buffer(encoder, (BIN_COUNT + 1L) * COMMAND_BYTES, GpuBuffer.USAGE_INDIRECT_PARAMETERS);
        var overflow = buffer(encoder, 4 * Integer.BYTES, GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC);
        runtime.clearBuffer(counts, 0);
        runtime.clearBuffer(overflow, 0);

        var buffers = Map.of(
                "NaniteClusterPages", pages.pages().slice(),
                "NaniteVisibleClusters", visible.clusters(),
                "NaniteCullCounts", visible.counters(),
                "NaniteBinCounts", counts,
                "NaniteBinOffsets", offsets,
                "NaniteBinCursors", cursors,
                "NaniteBinTriangles", triangles,
                "NaniteRasterCommands", commands,
                "NaniteRasterOverflow", overflow);
        var constants = Map.of("NaniteStreamingSlots", scalar(pages.streamingSlots()),
                "NaniteClusterCapacity", scalar(visible.clusterCapacity()), "NaniteTriangleCapacity", scalar(triangleCapacity),
                "NaniteClusterDispatchWidth", scalar(columns));
        int width = Math.multiplyExact(columns, CLUSTER_WORKGROUP);
        count.dispatch(encoder, buffers, constants, width, rows);
        allocate.dispatch(encoder, buffers, constants, BIN_COUNT, 1);
        scatter.dispatch(encoder, buffers, constants, width, rows);
        return new Output(triangles, offsets, commands, overflow, triangleCapacity);
    }

    private static byte[] scalar(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.nativeOrder()).putInt(value).array();
    }

    private GpuBufferSlice buffer(CommandEncoder encoder, long size, int usage) {
        if (size > runtime.maxStorageBufferBytes()) throw new IllegalArgumentException("Nanite raster buffer exceeds the storage limit");
        return encoder.transientMemory().allocateGpu(size, Math.max(16, runtime.storageBufferAlignment()), ComputeUsage.storageBuffer(usage));
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        try { scatter.close(); }
        finally {
            try { allocate.close(); }
            finally { count.close(); }
        }
    }
}
