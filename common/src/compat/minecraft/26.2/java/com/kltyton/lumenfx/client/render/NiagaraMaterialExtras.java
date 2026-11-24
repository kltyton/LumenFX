package com.kltyton.lumenfx.client.render;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Fenced per-frame texel buffers for Niagara material and ribbon particle attributes. */
final class NiagaraMaterialExtras implements AutoCloseable {
    record Chunk(int firstInstance, int instances, GpuBuffer buffer, GpuBufferSlice info, EffectMesh source) { }
    private static final class Slot {
        GpuBuffer buffer;
        long capacity;
    }
    private final List<List<Slot>> rings = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    private long maximumBytes;
    private int ring, nextSlot;

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
        List<Chunk> result = new ArrayList<>();
        int batchInstance = 0;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        for (EffectMesh mesh : meshes) {
            int stride = mesh.extraFloatsPerInstance();
            long bytesPerInstance = (long) stride * Float.BYTES;
            int maxInstances = (int) Math.min(Integer.MAX_VALUE, maximumBytes / bytesPerInstance);
            if (maxInstances < 1) throw new IllegalStateException("Device cannot fit Niagara dynamic material data");
            for (int first = 0; first < mesh.instanceCount();) {
                int count = Math.min(maxInstances, mesh.instanceCount() - first);
                long bytes = Math.multiplyExact((long) count, bytesPerInstance);
                Slot slot = slot(bytes);
                GpuBufferSlice uploadSlice;
                try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                    upload.data().order(ByteOrder.nativeOrder()).asFloatBuffer()
                            .put(mesh.extraValues(), first * stride, count * stride);
                    uploadSlice = upload.slice();
                }
                encoder.copyToBuffer(uploadSlice, slot.buffer.slice(0, bytes));
                GpuBufferSlice info;
                try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
                    mapped.data().order(ByteOrder.nativeOrder()).putInt(0).putInt(stride).putInt(mesh.key().componentVelocity() ? 1 : 0)
                            .putInt(mesh.needsMaterialBasis() ? mesh.materialBasisOffset() : 0);
                    info = mapped.slice();
                }
                result.add(new Chunk(batchInstance + first, count, slot.buffer, info, mesh));
                first += count;
            }
            batchInstance += mesh.instanceCount();
        }
        return List.copyOf(result);
    }

    private Slot slot(long bytes) {
        List<Slot> slots = rings.get(ring);
        if (nextSlot == slots.size()) slots.add(new Slot());
        Slot slot = slots.get(nextSlot++);
        if (slot.buffer == null || slot.capacity < bytes) {
            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> "Niagara dynamic material data",
                    com.kltyton.lumenfx.client.render.compute.ComputeUsage.storageBuffer(
                            GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST), bytes);
            if (slot.buffer != null) slot.buffer.close();
            slot.buffer = replacement;
            slot.capacity = bytes;
        }
        return slot;
    }

    @Override public void close() {
        for (List<Slot> slots : rings) {
            for (Slot slot : slots) if (slot.buffer != null) slot.buffer.close();
            slots.clear();
        }
        maximumBytes = 0;
    }
}
