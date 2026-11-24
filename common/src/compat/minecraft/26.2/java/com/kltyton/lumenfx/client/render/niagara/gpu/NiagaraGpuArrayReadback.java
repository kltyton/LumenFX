package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/** Preserves ordered GPU-to-CPU array readbacks without blocking the render thread. */
final class NiagaraGpuArrayReadback implements AutoCloseable {
    private record Pending(GpuBuffer staging, GpuFence fence, int width, int capacity, String type, long cacheEpoch) { }
    private final String name;
    private final NiagaraInterfaces.ArrayState target;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private boolean closed;

    NiagaraGpuArrayReadback(String name, NiagaraInterfaces.ArrayState target) {
        this.name = name;
        this.target = target;
    }

    void submit(CommandEncoder encoder, GpuBufferSlice count, GpuBufferSlice data, int width, int capacity, String type) {
        RenderSystem.assertOnRenderThread();
        if (closed || count.length() != Integer.BYTES || width < 1 || capacity < 0)
            throw new IllegalArgumentException("Invalid Niagara array readback");
        long dataBytes = Math.multiplyExact(Math.multiplyExact((long) capacity, width), Integer.BYTES);
        GpuBuffer staging = RenderSystem.getDevice().createBuffer(() -> name + ".ArrayReadback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, Integer.BYTES + dataBytes);
        GpuFence fence = null;
        try {
            encoder.copyToBuffer(count, staging.slice(0, Integer.BYTES));
            if (dataBytes > 0) encoder.copyToBuffer(data.slice(0, dataBytes), staging.slice(Integer.BYTES, dataBytes));
            fence = encoder.createFence();
            pending.addLast(new Pending(staging, fence, width, capacity, type, target.cacheEpoch()));
        } catch (RuntimeException | Error error) {
            if (fence != null) fence.close();
            staging.close();
            throw error;
        }
    }

    void poll() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara array readback is closed");
        while (!pending.isEmpty() && pending.peekFirst().fence().awaitCompletion(0L)) {
            Pending sample = pending.removeFirst();
            try {
                if (sample.cacheEpoch() != target.cacheEpoch()) continue;
                int count;
                try (var mapped = sample.staging().slice(0, Integer.BYTES).map(true, false)) {
                    count = mapped.data().order(ByteOrder.nativeOrder()).getInt(0);
                }
                if (count < 0 || count > sample.capacity()) throw new IllegalStateException("Invalid GPU Niagara array count " + count);
                int[] words = new int[Math.multiplyExact(count, sample.width())];
                final int chunkWords = 4 * 1024 * 1024;
                for (int start = 0; start < words.length;) {
                    int length = Math.min(chunkWords, words.length - start);
                    try (var mapped = sample.staging().slice(Integer.BYTES + (long) start * Integer.BYTES,
                            (long) length * Integer.BYTES).map(true, false)) {
                        var data = mapped.data().order(ByteOrder.nativeOrder());
                        for (int i = 0; i < length; i++) {
                            int value = data.getInt(i * Integer.BYTES);
                            if (sample.type().equals("NiagaraDataInterfaceArrayBool")) value = value == 0 ? 0 : -1;
                            else if (sample.type().equals("NiagaraDataInterfaceArrayUInt8")) value &= 255;
                            words[start + i] = value;
                        }
                    }
                    start += length;
                }
                target.applyGpu(words, sample.cacheEpoch());
            } finally {
                sample.fence().close(); sample.staging().close();
            }
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (Pending sample : pending) { sample.fence().close(); sample.staging().close(); }
        pending.clear();
    }
}
