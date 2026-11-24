package com.kltyton.lumenfx.client.render.niagara.gpu.export;

import com.kltyton.lumenfx.api.client.parameter.NiagaraParticleExport.Particle;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportState;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.export.NiagaraParticleExportState.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** World-owned GPU export readbacks are delivered after their fences signal. */
public final class NiagaraGpuParticleExportReadbacks {
    private record Pending(GpuBuffer staging, GpuFence fence, int capacity, Snapshot handler) implements AutoCloseable {
        @Override public void close() {
            try { fence.close(); }
            finally { staging.close(); }
        }
    }

    private static final Map<ClientLevel, ArrayDeque<Pending>> WORLDS = new IdentityHashMap<>();
    private static final int RECORD_BYTES = 7 * Float.BYTES;

    private NiagaraGpuParticleExportReadbacks() { }

    static long size(int capacity) { return Integer.BYTES + (long)capacity * RECORD_BYTES; }

    static void submit(ClientLevel level, CommandEncoder encoder, GpuBufferSlice source, int capacity, Snapshot handler) {
        RenderSystem.assertOnRenderThread();
        long length = size(capacity);
        var staging = RenderSystem.getDevice().createBuffer(() -> "Niagara particle export readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, length);
        GpuFence fence = null;
        try {
            encoder.copyToBuffer(source.slice(0, length), staging.slice());
            fence = encoder.createFence();
            WORLDS.computeIfAbsent(level, ignored -> new ArrayDeque<>()).addLast(new Pending(staging, fence, capacity, handler));
        } catch (RuntimeException | Error failure) {
            try { if (fence != null) fence.close(); }
            finally { staging.close(); }
            throw failure;
        }
    }

    public static void poll(ClientLevel level) {
        var pending = WORLDS.get(level);
        if (pending == null) return;
        RenderSystem.assertOnRenderThread();
        while (!pending.isEmpty() && pending.peekFirst().fence().awaitCompletion(0L)) {
            try (var readback = pending.removeFirst()) {
                int count;
                try (var header = readback.staging().slice(0, Integer.BYTES).map(true, false)) {
                    count = header.data().order(ByteOrder.nativeOrder()).getInt(0);
                }
                if (count < 0 || count > readback.capacity())
                    throw new IllegalStateException("GPU Niagara export count exceeds the stage allocation: " + Integer.toUnsignedString(count));
                if (count == 0) continue;
                var samples = new ArrayList<Particle>(count);
                try (var mapped = readback.staging().slice(Integer.BYTES, (long)count * RECORD_BYTES).map(true, false)) {
                    var data = mapped.data().order(ByteOrder.nativeOrder());
                    for (int index = 0; index < count; index++) {
                        var position = new Vec3(data.getFloat(), data.getFloat(), data.getFloat());
                        float size = data.getFloat();
                        var velocity = new Vec3(data.getFloat(), data.getFloat(), data.getFloat());
                        samples.add(new Particle(position, size, velocity));
                    }
                }
                NiagaraParticleExportState.deliver(level, readback.handler(), samples);
            }
        }
        if (pending.isEmpty()) WORLDS.remove(level);
    }

    public static void clear(ClientLevel level) {
        var pending = WORLDS.remove(level);
        if (pending != null) for (var value : pending) value.close();
    }

    public static void clear() {
        for (var pending : WORLDS.values()) for (var value : pending) value.close();
        WORLDS.clear();
    }
}
