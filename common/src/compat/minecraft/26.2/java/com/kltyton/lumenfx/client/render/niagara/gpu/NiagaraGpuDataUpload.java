package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Copies CPU component words into the common GPU dataset layout without owning simulation rotation or count slots. */
public final class NiagaraGpuDataUpload {
    private NiagaraGpuDataUpload() { }

    public static void data(CommandEncoder encoder, VectorDataSet source, NiagaraGpuDataBuffer destination) {
        int count = source.count();
        if (count > destination.stride()) throw new IllegalArgumentException("CPU particles exceed the GPU destination stride");
        if (count == 0) return;
        copy(encoder, source, destination.floats(), 0, destination.stride(), count);
        copy(encoder, source, destination.integers(), 1, destination.stride(), count);
        copy(encoder, source, destination.halves(), 2, destination.stride(), count);
    }

    public static void count(CommandEncoder encoder, GpuBufferSlice counters, int offset, int count) {
        GpuBufferSlice counter;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(count);
            counter = mapped.slice();
        }
        encoder.copyToBuffer(counter, counters.slice((long)offset * Integer.BYTES, Integer.BYTES));
    }

    private static void copy(CommandEncoder encoder, VectorDataSet source, @Nullable GpuBuffer target,
                             int type, int stride, int count) {
        if (target == null) return;
        int elementBytes = type == 2 ? Short.BYTES : Integer.BYTES;
        int components = Math.toIntExact(target.size() / ((long)stride * elementBytes));
        GpuBufferSlice upload;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(target.size(), Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            MemoryUtil.memSet(MemoryUtil.memAddress(bytes), 0, bytes.remaining());
            if (type == 2) {
                var halves = bytes.asShortBuffer();
                for (int component = 0; component < components; component++) {
                    halves.position(Math.multiplyExact(component, stride));
                    int[] values = source.component(type, component);
                    for (int row = 0; row < count; row++) halves.put((short)values[row]);
                }
            } else {
                var words = bytes.asIntBuffer();
                for (int component = 0; component < components; component++)
                    words.position(Math.multiplyExact(component, stride)).put(source.component(type, component), 0, count);
            }
            upload = mapped.slice();
        }
        encoder.copyToBuffer(upload, target.slice());
    }
}
