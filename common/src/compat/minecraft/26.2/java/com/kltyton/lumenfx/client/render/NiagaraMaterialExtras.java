package com.kltyton.lumenfx.client.render;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Fenced per-frame texel buffers for Niagara material and ribbon particle attributes.
 *
 * <p>Writes directly into fenced ring buffers via {@link CommandEncoder#writeToBuffer} from a reusable
 * native staging buffer (eliminating redundant transient staging allocations and GPU-to-GPU buffer copies)
 * and batches all per-chunk {@code NiagaraExtraInfo} uniform blocks into one transient allocation.</p>
 */
final class NiagaraMaterialExtras implements AutoCloseable {
    private static final int RING_COUNT = 4;
    private static final long MIN_SLOT_BYTES = 4096L;
    private static final int INFO_BYTES = 16;

    record Chunk(int firstInstance, int instances, GpuBuffer buffer, GpuBufferSlice info, EffectMesh source) { }

    private static final class Slot {
        GpuBuffer buffer;
        long capacity;
    }

    private final List<List<Slot>> rings;
    private @Nullable ByteBuffer staging;
    private @Nullable MemorySegment stagingSegment;
    private long maximumBytes;
    private int ring, nextSlot;

    NiagaraMaterialExtras() {
        List<List<Slot>> list = new ArrayList<>(RING_COUNT);
        for (int i = 0; i < RING_COUNT; i++) list.add(new ArrayList<>());
        rings = List.copyOf(list);
    }

    void beginFrame() {
        RenderSystem.assertOnRenderThread();
        ring = (ring + 1) % rings.size();
        nextSlot = 0;
        if (maximumBytes == 0) {
            try (ComputeRuntime runtime = ComputeRuntime.create(RenderSystem.getDevice())) {
                maximumBytes = Math.min(runtime.maxTexelBufferElements() * Float.BYTES,
                        Math.min(runtime.maxStorageBufferBytes(), RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize()));
            }
        }
    }

    List<Chunk> prepare(CommandEncoder encoder, List<EffectMesh> meshes) {
        int totalChunks = 0;
        for (int i = 0, n = meshes.size(); i < n; i++) {
            EffectMesh mesh = meshes.get(i);
            int instanceCount = mesh.instanceCount();
            if (instanceCount <= 0) continue;
            long bytesPerInstance = (long) mesh.extraFloatsPerInstance() * Float.BYTES;
            int maxInstances = (int) Math.min(Integer.MAX_VALUE, maximumBytes / bytesPerInstance);
            if (maxInstances < 1) throw new IllegalStateException("Device cannot fit Niagara dynamic material data");
            totalChunks += Math.ceilDiv(instanceCount, maxInstances);
        }
        if (totalChunks == 0) return List.of();

        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int infoStride = ((INFO_BYTES + alignment - 1) / alignment) * alignment;
        List<Chunk> result = new ArrayList<>(totalChunks);
        int batchInstance = 0;
        int chunkIndex = 0;

        try (var mappedInfo = encoder.transientMemory().allocateGpuMapped(
                (long) totalChunks * infoStride, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer infoData = mappedInfo.data().order(ByteOrder.nativeOrder());
            GpuBufferSlice infoBase = mappedInfo.slice();
            for (int i = 0, n = meshes.size(); i < n; i++) {
                EffectMesh mesh = meshes.get(i);
                int instanceCount = mesh.instanceCount();
                if (instanceCount <= 0) continue;
                int stride = mesh.extraFloatsPerInstance();
                long bytesPerInstance = (long) stride * Float.BYTES;
                int maxInstances = (int) Math.min(Integer.MAX_VALUE, maximumBytes / bytesPerInstance);
                int componentVelocity = mesh.key().componentVelocity() ? 1 : 0;
                int basisOffset = mesh.needsMaterialBasis() ? mesh.materialBasisOffset() : 0;
                float[] extraValues = mesh.extraValues();
                for (int first = 0; first < instanceCount; ) {
                    int count = Math.min(maxInstances, instanceCount - first);
                    int floatCount = Math.multiplyExact(count, stride);
                    long bytes = Math.multiplyExact((long) floatCount, Float.BYTES);
                    Slot slot = slot(bytes);
                    ByteBuffer uploadView = stageFloats(extraValues, first * stride, floatCount, (int) bytes);
                    encoder.writeToBuffer(slot.buffer.slice(0, bytes), uploadView);

                    long infoOffset = (long) chunkIndex * infoStride;
                    infoData.position((int) infoOffset);
                    infoData.putInt(0).putInt(stride).putInt(componentVelocity).putInt(basisOffset);
                    GpuBufferSlice info = infoBase.slice(infoOffset, INFO_BYTES);
                    result.add(new Chunk(batchInstance + first, count, slot.buffer, info, mesh));
                    first += count;
                    chunkIndex++;
                }
                batchInstance += instanceCount;
            }
        }
        return result;
    }

    private ByteBuffer stageFloats(float[] source, int floatOffset, int floatCount, int byteLength) {
        ByteBuffer buffer = staging;
        if (buffer == null || buffer.capacity() < byteLength) {
            int nextCap = Math.max(byteLength, buffer == null ? 65536 : Math.min(Integer.MAX_VALUE - 16, buffer.capacity() * 2));
            ByteBuffer replacement = MemoryUtil.memAlloc(nextCap);
            if (buffer != null) MemoryUtil.memFree(buffer);
            staging = buffer = replacement;
            stagingSegment = MemorySegment.ofBuffer(buffer);
        }
        MemorySegment.copy(source, floatOffset, stagingSegment, ValueLayout.JAVA_FLOAT_UNALIGNED, 0L, floatCount);
        return buffer.slice(0, byteLength);
    }

    private Slot slot(long bytes) {
        List<Slot> slots = rings.get(ring);
        if (nextSlot == slots.size()) slots.add(new Slot());
        Slot slot = slots.get(nextSlot++);
        if (slot.buffer == null || slot.capacity < bytes) {
            long targetCapacity = Math.min(maximumBytes, Math.max(bytes, Math.max(MIN_SLOT_BYTES, slot.capacity * 2L)));
            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> "Niagara dynamic material data",
                    com.kltyton.lumenfx.client.render.compute.ComputeUsage.storageBuffer(
                            GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST), targetCapacity);
            if (slot.buffer != null) slot.buffer.close();
            slot.buffer = replacement;
            slot.capacity = targetCapacity;
        }
        return slot;
    }

    @Override public void close() {
        for (List<Slot> slots : rings) {
            for (Slot slot : slots) if (slot.buffer != null) slot.buffer.close();
            slots.clear();
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
            stagingSegment = null;
        }
        maximumBytes = 0;
    }
}
