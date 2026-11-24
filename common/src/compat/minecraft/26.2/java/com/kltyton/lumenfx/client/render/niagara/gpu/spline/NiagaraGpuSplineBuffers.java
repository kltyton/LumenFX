package com.kltyton.lumenfx.client.render.niagara.gpu.spline;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSplineLut;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSplineLut.Channel;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/** Render-thread LUT resources, keyed by the physical interface rather than a shader alias. */
public final class NiagaraGpuSplineBuffers implements AutoCloseable {
    private record Entry(NiagaraSplineLut lut, Map<Channel, GpuBuffer> buffers) {
        void close() { buffers.values().forEach(GpuBuffer::close); }
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private final ComputeRuntime runtime;

    public NiagaraGpuSplineBuffers(ComputeRuntime runtime) { this.runtime = runtime; }

    GpuBufferSlice buffer(String id, NiagaraSplineLut lut, Channel channel) {
        var entry = entries.get(id);
        if (entry == null || entry.lut() != lut) {
            var replacement = upload(id, lut);
            entries.put(id, replacement);
            if (entry != null) entry.close();
            entry = replacement;
        }
        return entry.buffers().get(channel).slice();
    }

    private Entry upload(String id, NiagaraSplineLut lut) {
        int size = lut.bytes();
        if (size > runtime.maxStorageBufferBytes()) throw new IllegalArgumentException("Niagara Spline LUT exceeds storage capacity: " + id);
        var bytes = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
        var result = new EnumMap<Channel, GpuBuffer>(Channel.class);
        try {
            for (var channel : Channel.values()) {
                bytes.clear();
                lut.write(channel, bytes);
                bytes.flip();
                result.put(channel, RenderSystem.getDevice().createBuffer(() -> id + ".Spline." + channel,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes));
            }
            return new Entry(lut, result);
        } catch (RuntimeException | Error failure) {
            result.values().forEach(GpuBuffer::close);
            throw failure;
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    @Override public void close() {
        entries.values().forEach(Entry::close);
        entries.clear();
    }
}
