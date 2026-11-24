package com.kltyton.lumenfx.client.render.niagara.gpu.geometry;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionFrame.Buffer;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Render-side buffers owned by each physical Geometry Collection data interface. */
public final class NiagaraGpuGeometryBuffers implements AutoCloseable {
    static final class Entry {
        private final Map<Buffer, GpuBuffer> buffers = new EnumMap<>(Buffer.class);
        private @Nullable GeometryCollectionFrame frame;
        private final int count;

        Entry(String identity, GeometryCollectionFrame data, long limit) {
            count = data.count();
            try {
                for (var buffer : Buffer.values()) {
                    int bytes = data.bytes(buffer);
                    if (bytes == 0) continue;
                    if (bytes > limit) throw new IllegalArgumentException("Niagara Geometry Collection buffer exceeds storage limits: " + identity);
                    buffers.put(buffer, RenderSystem.getDevice().createBuffer(() -> identity + ".Geometry." + buffer,
                            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes));
                }
            } catch (RuntimeException | Error failure) {
                buffers.values().forEach(GpuBuffer::close);
                throw failure;
            }
        }

        @Nullable GpuBufferSlice buffer(Buffer kind) {
            var buffer = buffers.get(kind);
            return buffer == null ? null : buffer.slice();
        }

        void upload(CommandEncoder encoder, GeometryCollectionFrame data) {
            if (frame == data) return;
            int largest = 0;
            for (var buffer : buffers.keySet()) largest = Math.max(largest, data.bytes(buffer));
            var bytes = MemoryUtil.memAlloc(largest).order(ByteOrder.LITTLE_ENDIAN);
            try {
                for (var item : buffers.entrySet()) {
                    bytes.clear();
                    data.write(item.getKey(), bytes);
                    bytes.flip();
                    encoder.writeToBuffer(item.getValue().slice(), bytes);
                }
                frame = data;
            } finally {
                MemoryUtil.memFree(bytes);
            }
        }

        void retire() { RenderSystem.queueFencedTask(() -> buffers.values().forEach(GpuBuffer::close)); }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final ComputeRuntime runtime;

    public NiagaraGpuGeometryBuffers(ComputeRuntime runtime) { this.runtime = runtime; }

    Entry prepare(CommandEncoder encoder, String identity, GeometryCollectionFrame frame) {
        var previous = entries.get(identity);
        if (previous != null && previous.count == frame.count()) {
            previous.upload(encoder, frame);
            return previous;
        }
        var next = new Entry(identity, frame, runtime.maxStorageBufferBytes());
        try { next.upload(encoder, frame); }
        catch (RuntimeException | Error failure) { next.retire(); throw failure; }
        entries.put(identity, next);
        if (previous != null) previous.retire();
        return next;
    }

    @Override public void close() {
        entries.values().forEach(Entry::retire);
        entries.clear();
    }
}
