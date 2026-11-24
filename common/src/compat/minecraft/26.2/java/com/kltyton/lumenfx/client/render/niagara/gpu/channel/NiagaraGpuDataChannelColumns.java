package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** One Niagara GPU channel data buffer, with float and integer columns and a live append count. */
final class NiagaraGpuDataChannelColumns implements AutoCloseable {
    private final GpuBuffer floats, integers, count;
    private final int capacity;

    NiagaraGpuDataChannelColumns(NiagaraGpuPrograms programs, NiagaraDataChannelGpuSnapshot initial, int capacity, String name) {
        if (capacity < initial.rows() || capacity <= 0) throw new IllegalArgumentException("Invalid Niagara GPU channel capacity");
        this.capacity = capacity;
        var floatColumns = upload(programs, name + ".Float", initial.columnBytes(true, capacity),
                data -> initial.writeColumns(true, capacity, data));
        try {
            var intColumns = upload(programs, name + ".Int32", initial.columnBytes(false, capacity),
                    data -> initial.writeColumns(false, capacity, data));
            try {
                count = upload(programs, name + ".Count", Integer.BYTES, data -> data.putInt(initial.rows()));
            } catch (RuntimeException | Error failure) {
                intColumns.close();
                throw failure;
            }
            integers = intColumns;
        } catch (RuntimeException | Error failure) {
            floatColumns.close();
            throw failure;
        }
        floats = floatColumns;
    }

    private static GpuBuffer upload(NiagaraGpuPrograms programs, String name, long size, Consumer<ByteBuffer> write) {
        if (size > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara data channel column allocation exceeds device capacity: " + name);
        var data = MemoryUtil.memAlloc(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            write.accept(data);
            data.flip();
            return RenderSystem.getDevice().createBuffer(() -> name,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), data);
        } finally { MemoryUtil.memFree(data); }
    }

    int capacity() { return capacity; }
    GpuBuffer floats() { return floats; }
    GpuBuffer integers() { return integers; }
    GpuBuffer count() { return count; }

    @Override public void close() {
        try { count.close(); }
        finally {
            try { integers.close(); }
            finally { floats.close(); }
        }
    }
}
