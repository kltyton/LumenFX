package com.kltyton.lumenfx.client.render.niagara.gpu.physics;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics.NiagaraPhysicsAssetReader.Frame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
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

/** The physical Physics Asset DI owns one mutable GPU buffer set for the system. */
public final class NiagaraGpuPhysicsBuffers implements AutoCloseable {
    private static final class Entry implements AutoCloseable {
        private final Map<Buffer, GpuBuffer> buffers = new EnumMap<>(Buffer.class);
        private @Nullable Frame frame;

        Entry(String identity) {
            try {
                for (var kind : Buffer.values()) buffers.put(kind, RenderSystem.getDevice().createBuffer(
                        () -> identity + ".PhysicsAsset." + kind, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), Frame.bytes(kind)));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
        @Override public void close() { buffers.values().forEach(GpuBuffer::close); }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    void upload(CommandEncoder encoder, String identity, Frame frame) {
        var entry = entries.computeIfAbsent(SourceNames.canonical(identity), ignored -> new Entry(identity));
        if (entry.frame == frame) return;
        var bytes = MemoryUtil.memAlloc(Frame.bytes(Buffer.WORLD)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            for (var buffer : entry.buffers.entrySet()) {
                bytes.clear();
                frame.write(buffer.getKey(), bytes);
                bytes.flip();
                encoder.writeToBuffer(buffer.getValue().slice(), bytes);
            }
            entry.frame = frame;
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    @Nullable GpuBufferSlice buffer(String identity, Buffer kind) {
        var entry = entries.get(SourceNames.canonical(identity));
        return entry == null ? null : entry.buffers.get(kind).slice();
    }

    @Override public void close() {
        entries.values().forEach(Entry::close);
        entries.clear();
    }
}
