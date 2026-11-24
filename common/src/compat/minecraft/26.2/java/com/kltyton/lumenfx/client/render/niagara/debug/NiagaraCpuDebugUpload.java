package com.kltyton.lumenfx.client.render.niagara.debug;

import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines.Frame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraCpuDebugLines.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Publishes CPU line words to the same vertex stream used by GPU Niagara stages. */
final class NiagaraCpuDebugUpload implements AutoCloseable {
    // A chunk fits the minimum texel-buffer capacity of both host graphics APIs.
    private static final int LINES_PER_UPLOAD = 8192;
    private final List<GpuBuffer> buffers = new ArrayList<>();

    void append(Snapshot source, List<Frame> frames) {
        for (int start = 0; start < source.lineCount();) {
            int count = Math.min(LINES_PER_UPLOAD, source.lineCount() - start);
            var data = MemoryUtil.memAlloc(count * NiagaraGpuDebugLines.LINE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            try (var stack = MemoryStack.stackPush()) {
                source.write(data, start, count);
                var device = RenderSystem.getDevice();
                var vertices = device.createBuffer(() -> "Niagara Debug CPU Lines", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, data.flip());
                buffers.add(vertices);
                var indirect = stack.malloc(5 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                indirect.putInt(2).putInt(count).putInt(0).putInt(0).putInt(0).flip();
                var command = device.createBuffer(() -> "Niagara Debug CPU Line Draw", GpuBuffer.USAGE_INDIRECT_PARAMETERS, indirect);
                buffers.add(command);
                frames.add(new Frame(vertices.slice(), command.slice(), source.sourceTile()));
            } finally { MemoryUtil.memFree(data); }
            start += count;
        }
    }

    @Override public void close() { buffers.forEach(GpuBuffer::close); }
}
