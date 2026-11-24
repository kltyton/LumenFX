package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import org.jspecify.annotations.Nullable;

/** Nonblocking particle-count snapshots; the GPU count remains authoritative for dispatch. */
public final class NiagaraGpuCountReadback implements AutoCloseable {
    public record Sample(long sequence, int count) { }
    private static final int SLOT_COUNT = 3;

    private static final class Slot {
        final GpuBuffer buffer;
        GpuFence fence;
        long sequence;
        Slot(GpuBuffer buffer) { this.buffer = buffer; }
    }

    private final ArrayDeque<Slot> free = new ArrayDeque<>();
    private final ArrayDeque<Slot> pending = new ArrayDeque<>();
    private boolean closed;

    public NiagaraGpuCountReadback(String name) {
        RenderSystem.assertOnRenderThread();
        try {
            for (int i = 0; i < SLOT_COUNT; i++) {
                String label = name + ".CountReadback" + i;
                free.addLast(new Slot(RenderSystem.getDevice().createBuffer(() -> label,
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, Integer.BYTES)));
            }
        } catch (RuntimeException | Error error) {
            free.forEach(slot -> slot.buffer.close());
            throw error;
        }
    }

    public boolean submit(CommandEncoder encoder, GpuBufferSlice count, long sequence) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara count readback is closed");
        if (count.length() != Integer.BYTES) throw new IllegalArgumentException("Count slice must contain one integer");
        Slot slot = free.pollFirst();
        if (slot == null) return false;
        try {
            encoder.copyToBuffer(count, slot.buffer.slice());
            slot.fence = encoder.createFence();
            slot.sequence = sequence;
            pending.addLast(slot);
            return true;
        } catch (RuntimeException | Error error) {
            slot.buffer.close();
            close();
            throw error;
        }
    }

    public @Nullable Sample poll() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara count readback is closed");
        Slot slot = pending.peekFirst();
        if (slot == null || !slot.fence.awaitCompletion(0L)) return null;
        pending.removeFirst();
        int count;
        try (var mapped = slot.buffer.map(true, false)) {
            count = mapped.data().order(ByteOrder.nativeOrder()).getInt(0);
        } finally {
            slot.fence.close();
            slot.fence = null;
            free.addLast(slot);
        }
        return new Sample(slot.sequence, count);
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (Slot slot : pending) {
            slot.fence.close();
            slot.buffer.close();
        }
        for (Slot slot : free) slot.buffer.close();
        pending.clear();
        free.clear();
    }
}
