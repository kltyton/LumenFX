package com.kltyton.lumenfx.client.render.niagara.gpu.chaos;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/** GPU column storage shared by stages reading the same physical interface. */
public final class NiagaraGpuChaosBuffers implements AutoCloseable {
    private record Key(String identity, Column column) { }
    private record Entry(GpuBuffer buffer, ChaosDestructionFrame frame) { }

    private final Map<Key, Entry> entries = new HashMap<>();
    private final ComputeRuntime runtime;

    public NiagaraGpuChaosBuffers(ComputeRuntime runtime) { this.runtime = runtime; }

    GpuBufferSlice prepare(CommandEncoder encoder, String identity, Column column, ChaosDestructionFrame frame) {
        var key = new Key(identity, column);
        var previous = entries.get(key);
        int size = frame.bytes(column);
        if (previous != null && previous.frame() == frame) return previous.buffer().slice(0, size);
        if (size > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara Chaos Destruction buffer exceeds storage limits: " + identity + "." + column.symbol());

        boolean allocate = previous == null || previous.buffer().size() < size;
        GpuBuffer buffer = allocate ? RenderSystem.getDevice().createBuffer(() -> identity + "." + column.symbol(),
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), size) : previous.buffer();
        var slice = buffer.slice(0, size);
        try {
            var bytes = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
            try {
                frame.write(column, bytes);
                bytes.flip();
                encoder.writeToBuffer(slice, bytes);
            } finally {
                MemoryUtil.memFree(bytes);
            }
        } catch (RuntimeException | Error failure) {
            if (allocate) RenderSystem.queueFencedTask(buffer::close);
            throw failure;
        }
        entries.put(key, new Entry(buffer, frame));
        if (allocate && previous != null) RenderSystem.queueFencedTask(previous.buffer()::close);
        return slice;
    }

    @Override public void close() {
        for (var item : entries.values()) RenderSystem.queueFencedTask(item.buffer()::close);
        entries.clear();
    }
}
