package com.kltyton.lumenfx.client.render;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Fenced multi-frame ring vertex uploader for CPU-packed particle instance streams.
 *
 * <p>Avoids per-frame {@code GlTransientMemory} 512 KB block overflow thrashing ({@code malloc}/{@code free} and
 * {@code glCreateBuffers}/{@code glDeleteBuffers}) and copies float arrays directly into a reusable native staging
 * buffer via {@link MemorySegment#copy} before issuing a single {@link CommandEncoder#writeToBuffer}.</p>
 */
final class VertexStreamRing implements AutoCloseable {
    private static final int RING_COUNT = 4;
    private static final long MIN_SLOT_BYTES = 65536L;

    private static final class Slot {
        GpuBuffer buffer;
        long capacity;
        boolean storageEnabled;
    }

    private final List<List<Slot>> rings;
    private @Nullable ByteBuffer staging;
    private @Nullable MemorySegment stagingSegment;
    private int ring;
    private int nextSlot;
    private @Nullable Object lastFrameIdentity;

    VertexStreamRing() {
        List<List<Slot>> list = new ArrayList<>(RING_COUNT);
        for (int i = 0; i < RING_COUNT; i++) list.add(new ArrayList<>());
        rings = List.copyOf(list);
    }

    void beginDraw(Object frameIdentity) {
        RenderSystem.assertOnRenderThread();
        if (frameIdentity != lastFrameIdentity) {
            ring = (ring + 1) % rings.size();
            nextSlot = 0;
            lastFrameIdentity = frameIdentity;
        }
    }

    GpuBufferSlice upload(CommandEncoder encoder, long totalBytes, boolean storageBuffer, List<EffectGpuRenderer.Batch> drawing) {
        int byteLength = Math.toIntExact(totalBytes);
        ByteBuffer buffer = ensureStaging(byteLength);
        MemorySegment destination = stagingSegment;
        long byteOffset = 0L;
        for (int i = 0, n = drawing.size(); i < n; i++) {
            List<EffectMesh> parts = drawing.get(i).parts;
            for (int j = 0, m = parts.size(); j < m; j++) {
                EffectMesh mesh = parts.get(j);
                int floatCount = mesh.floatCount();
                if (floatCount > 0) {
                    MemorySegment.copy(mesh.values(), 0, destination, ValueLayout.JAVA_FLOAT_UNALIGNED, byteOffset, floatCount);
                    byteOffset += (long) floatCount * Float.BYTES;
                }
            }
        }
        Slot slot = acquireSlot(totalBytes, storageBuffer);
        GpuBufferSlice slice = slot.buffer.slice(0, totalBytes);
        encoder.writeToBuffer(slice, buffer.slice(0, byteLength));
        return slice;
    }

    private ByteBuffer ensureStaging(int byteLength) {
        ByteBuffer buffer = staging;
        if (buffer == null || buffer.capacity() < byteLength) {
            int nextCapacity = Math.max(byteLength, buffer == null ? (int) MIN_SLOT_BYTES
                    : Math.min(Integer.MAX_VALUE - 16, buffer.capacity() * 2));
            ByteBuffer replacement = MemoryUtil.memAlloc(nextCapacity);
            if (buffer != null) MemoryUtil.memFree(buffer);
            staging = buffer = replacement;
            stagingSegment = MemorySegment.ofBuffer(buffer);
        }
        return buffer;
    }

    private Slot acquireSlot(long bytes, boolean storageBuffer) {
        List<Slot> slots = rings.get(ring);
        if (nextSlot == slots.size()) slots.add(new Slot());
        Slot slot = slots.get(nextSlot++);
        if (slot.buffer == null || slot.capacity < bytes || (storageBuffer && !slot.storageEnabled)) {
            long targetCapacity = Math.max(bytes, Math.max(MIN_SLOT_BYTES, slot.capacity * 2L));
            int usage = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;
            if (storageBuffer) usage = ComputeUsage.storageBuffer(usage);
            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(
                    () -> "LumenFX CPU instance vertices", usage, targetCapacity);
            if (slot.buffer != null) slot.buffer.close();
            slot.buffer = replacement;
            slot.capacity = targetCapacity;
            slot.storageEnabled = storageBuffer;
        }
        return slot;
    }

    @Override
    public void close() {
        for (List<Slot> slots : rings) {
            for (Slot slot : slots) {
                if (slot.buffer != null) slot.buffer.close();
            }
            slots.clear();
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
            stagingSegment = null;
        }
        lastFrameIdentity = null;
        nextSlot = 0;
    }
}
