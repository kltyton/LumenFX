package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/** One resource's hierarchy and roots address the world-owned cluster buffer. */
final class NaniteGpuPages implements AutoCloseable {
    private record Transfer(GpuBufferSlice source, GpuBufferSlice target) { }

    private final NanitePhysicalPagePool pool;
    private final GpuBuffer hierarchy, roots, assembly;

    private NaniteGpuPages(NanitePhysicalPagePool pool, GpuBuffer hierarchy, GpuBuffer roots, GpuBuffer assembly) {
        this.pool = pool;
        this.hierarchy = hierarchy;
        this.roots = roots;
        this.assembly = assembly;
    }

    static NaniteGpuPages upload(CommandEncoder encoder, NanitePhysicalPagePool pool, NaniteResidentPages source,
                                  @Nullable NaniteResidentPages previous, int rootCount) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        var hierarchy = source.hierarchy();
        var roots = source.roots();
        var assembly = source.assemblyTransforms();
        long maximum = device.getDeviceInfo().limits().maxMemoryAllocationSize();
        if (hierarchy.remaining() > maximum || roots.remaining() > maximum || assembly.remaining() > maximum)
            throw new IllegalArgumentException("Nanite hierarchy or assembly exceeds the device allocation size");
        int usage = ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST);
        GpuBuffer hierarchyBuffer = null, rootBuffer = null, assemblyBuffer = null;
        try {
            hierarchyBuffer = device.createBuffer(() -> "LumenFX Nanite hierarchy", usage, hierarchy.remaining());
            rootBuffer = device.createBuffer(() -> "LumenFX Nanite root nodes", usage, roots.remaining());
            assemblyBuffer = device.createBuffer(() -> "LumenFX Nanite assembly transforms", usage, Math.max(16, assembly.remaining()));
            var transfers = new ArrayList<Transfer>();
            transfers.add(stage(encoder, hierarchyBuffer, 0, hierarchy));
            transfers.add(stage(encoder, rootBuffer, 0, roots));
            if (assembly.hasRemaining()) transfers.add(stage(encoder, assemblyBuffer, 0, assembly));
            for (var page : source.uploads(previous))
                transfers.add(stage(encoder, pool.buffer(), pool.byteOffset(page.sourcePage(), page.slot(), rootCount), page.data()));
            for (var transfer : transfers) encoder.copyToBuffer(transfer.source(), transfer.target());
            return new NaniteGpuPages(pool, hierarchyBuffer, rootBuffer, assemblyBuffer);
        } catch (RuntimeException | Error failure) {
            if (assemblyBuffer != null) assemblyBuffer.close();
            if (rootBuffer != null) rootBuffer.close();
            if (hierarchyBuffer != null) hierarchyBuffer.close();
            throw failure;
        }
    }

    private static Transfer stage(CommandEncoder encoder, GpuBuffer target, long offset, ByteBuffer data) {
        int size = data.remaining();
        GpuBufferSlice copy;
        try (var staging = encoder.transientMemory().allocateGpuMapped(size, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            staging.data().put(data);
            copy = staging.slice();
        }
        return new Transfer(copy, target.slice(offset, size));
    }

    GpuBuffer pages() { return pool.buffer(); }
    GpuBuffer hierarchy() { return hierarchy; }
    GpuBuffer roots() { return roots; }
    GpuBuffer assembly() { return assembly; }
    int streamingSlots() { return pool.streamingSlots(); }

    @Override public void close() {
        try { assembly.close(); }
        finally {
            try { roots.close(); }
            finally { hierarchy.close(); }
        }
    }
}
