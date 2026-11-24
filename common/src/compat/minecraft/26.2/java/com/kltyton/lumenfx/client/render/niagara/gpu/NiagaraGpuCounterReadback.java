package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/** Ordered nonblocking counter readbacks for one system data-interface instance. */
final class NiagaraGpuCounterReadback implements AutoCloseable {
    private record Pending(GpuBuffer buffer, GpuFence fence, long cacheEpoch) { }
    private final String name;
    private final NiagaraInterfaces.CounterState target;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private boolean closed;

    NiagaraGpuCounterReadback(String name, NiagaraInterfaces.CounterState target) {
        this.name = name; this.target = target;
    }

    void submit(CommandEncoder encoder, GpuBufferSlice source) {
        if (closed || source.length() != Integer.BYTES) throw new IllegalArgumentException("Invalid Niagara counter readback");
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> name + ".CounterReadback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, Integer.BYTES);
        GpuFence fence = null;
        try {
            encoder.copyToBuffer(source, buffer.slice());
            fence = encoder.createFence();
            pending.addLast(new Pending(buffer, fence, target.cacheEpoch()));
        } catch (RuntimeException | Error error) {
            if (fence != null) fence.close();
            buffer.close();
            throw error;
        }
    }

    void poll() {
        while (!pending.isEmpty() && pending.peekFirst().fence().awaitCompletion(0L)) {
            Pending readback = pending.removeFirst();
            try (var mapped = readback.buffer().map(true, false)) {
                target.fromGpu(mapped.data().order(ByteOrder.nativeOrder()).getInt(0), readback.cacheEpoch());
            } finally {
                readback.fence().close(); readback.buffer().close();
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Pending readback : pending) { readback.fence().close(); readback.buffer().close(); }
        pending.clear();
    }
}
