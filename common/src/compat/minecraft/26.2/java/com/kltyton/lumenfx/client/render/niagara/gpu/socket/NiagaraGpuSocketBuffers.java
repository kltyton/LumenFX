package com.kltyton.lumenfx.client.render.niagara.gpu.socket;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader.Transforms;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/** One uploaded local-transform frame per physical socket reader. */
public final class NiagaraGpuSocketBuffers implements AutoCloseable {
    private record Buffer(Transforms frame, GpuBuffer storage) { }
    private final Map<String, Buffer> buffers = new HashMap<>();
    private final ComputeRuntime runtime;

    public NiagaraGpuSocketBuffers(ComputeRuntime runtime) { this.runtime = runtime; }

    public GpuBufferSlice buffer(String id, Transforms frame) {
        var previous = buffers.get(id);
        if (previous != null && previous.frame() == frame) return previous.storage().slice();
        int size = frame.bytes();
        if (size > runtime.maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara Socket Reader frame exceeds GPU storage capacity: " + id);
        var bytes = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
        try {
            frame.write(bytes);
            bytes.flip();
            var storage = RenderSystem.getDevice().createBuffer(() -> id + ".SocketData",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
            buffers.put(id, new Buffer(frame, storage));
            if (previous != null) previous.storage().close();
            return storage.slice();
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    @Override public void close() {
        buffers.values().forEach(buffer -> buffer.storage().close());
        buffers.clear();
    }
}
