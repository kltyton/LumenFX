package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One request buffer shared by the world's Nanite views and a four-slot asynchronous readback ring. */
final class NaniteGpuStreamingFeedback implements AutoCloseable {
    private static final int RECORD_BYTES = 3 * Integer.BYTES;
    private static final int MIN_ELEMENTS = 4 * 1024;
    private static final int MAX_ELEMENTS = 1024 * 1024;
    private static final int READBACKS = 4;

    record Output(GpuBufferSlice data, int capacity, int resourceId, int priorityCategory) { }

    private static final class Slot implements AutoCloseable {
        private @Nullable GpuBuffer buffer;
        private @Nullable GpuFence fence;
        private long frame;
        private int elements;

        void copy(CommandEncoder encoder, GpuBuffer source, long sequence, int size) {
            long bytes = source.size();
            if (buffer == null || buffer.size() != bytes) {
                var replacement = RenderSystem.getDevice().createBuffer(() -> "LumenFX Nanite streaming readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
                if (buffer != null) buffer.close();
                buffer = replacement;
            }
            encoder.copyToBuffer(source.slice(), buffer.slice());
            fence = encoder.createFence();
            frame = sequence;
            elements = size;
        }

        boolean ready() { return fence != null && fence.awaitCompletion(0L); }

        void release() {
            if (fence != null) fence.close();
            fence = null;
        }

        @Override public void close() {
            try { release(); }
            finally {
                if (buffer != null) buffer.close();
                buffer = null;
            }
        }
    }

    private final ComputeRuntime runtime;
    private final Slot[] slots = {new Slot(), new Slot(), new Slot(), new Slot()};
    private @Nullable GpuBuffer requests;
    private long frame = Long.MIN_VALUE;
    private int next, pending, elements;
    private int overBudget, underBudget;
    private float targetElements = 64 * 1024;

    NaniteGpuStreamingFeedback(ComputeRuntime runtime) { this.runtime = runtime; }

    long advance(CommandEncoder encoder, long sequence, int minElements, int maxElements,
                 Collection<NaniteMeshStreaming> resources, NaniteStreamingRequests authored, boolean useGpuRequests) {
        RenderSystem.assertOnRenderThread();
        if (frame == sequence) return -1;
        if (requests != null && pending < READBACKS) {
            slots[(next + pending) % READBACKS].copy(encoder, requests, frame, elements);
            pending++;
        }
        Slot latest = null;
        while (pending > 0 && slots[next].ready()) {
            latest = slots[next];
            latest.release();
            next = (next + 1) % READBACKS;
            pending--;
        }
        long unique = latest == null ? -1 : read(latest, resources, authored, useGpuRequests);

        int minimum = Math.clamp(minElements, MIN_ELEMENTS, MAX_ELEMENTS);
        int maximum = Math.clamp(maxElements, minimum, MAX_ELEMENTS);
        targetElements = Math.clamp(targetElements, minimum, maximum);
        long limit = Math.min(runtime.maxStorageBufferBytes(),
                RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize());
        int capacity = (int) Math.min(roundSize((int) targetElements), limit / RECORD_BYTES);
        if (capacity < 2) throw new IllegalStateException("Nanite streaming feedback cannot fit a request");
        if (capacity != elements && requests != null) {
            requests.close();
            requests = null;
        }
        if (requests == null) {
            requests = RenderSystem.getDevice().createBuffer(() -> "LumenFX Nanite streaming requests",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST),
                    (long) capacity * RECORD_BYTES);
        }
        runtime.clearBuffer(requests.slice(0, Integer.BYTES), 0);
        elements = capacity;
        frame = sequence;
        return unique;
    }

    @Nullable Output output(long sequence, NaniteMeshStreaming resource, int priorityCategory) {
        if (priorityCategory < 0) return null;
        if (frame != sequence || requests == null)
            throw new IllegalStateException("Nanite feedback used before its world update");
        return new Output(requests.slice(), elements - 1, resource.resourceId(), priorityCategory);
    }

    private long read(Slot slot, Collection<NaniteMeshStreaming> resources, NaniteStreamingRequests authored,
                      boolean useGpuRequests) {
        var byId = new HashMap<Integer, NaniteMeshStreaming>();
        for (var resource : resources) {
            byId.put(resource.resourceId(), resource);
            resource.beginRequests(slot.frame);
        }
        var buffer = Objects.requireNonNull(slot.buffer);
        try (var mapped = buffer.slice().map(true, false)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            long reported = Integer.toUnsignedLong(bytes.getInt(0));
            updateSize(reported);
            int count = useGpuRequests ? (int) Math.min(reported, slot.elements - 1L) : 0;
            bytes.position(RECORD_BYTES);
            for (int i = 0; i < count; i++) {
                var mesh = byId.get(bytes.getInt());
                int range = bytes.getInt();
                long priority = Integer.toUnsignedLong(bytes.getInt());
                if (mesh == null) continue;
                mesh.request(range, priority);
            }
        }
        authored.merge(resources);
        long pages = 0;
        for (var resource : resources) pages += resource.includeParentRequests();
        return pages;
    }

    private void updateSize(long requests) {
        float target = requests * 1.25f;
        overBudget = target > targetElements ? Math.min(overBudget + 1, 2) : 0;
        underBudget = requests < targetElements * 0.5f ? Math.min(underBudget + 1, 30) : 0;
        if (overBudget >= 2) targetElements = Math.max(targetElements, target);
        else if (underBudget >= 30) targetElements *= 0.98f;
    }

    private static int roundSize(int size) {
        int unit = Integer.highestOneBit(size) >> 1;
        return Math.ceilDiv(size, unit) * unit;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        for (var slot : slots) slot.close();
        if (requests != null) requests.close();
        requests = null;
        frame = Long.MIN_VALUE;
        next = pending = elements = 0;
        overBudget = underBudget = 0;
        targetElements = 64 * 1024;
    }
}
