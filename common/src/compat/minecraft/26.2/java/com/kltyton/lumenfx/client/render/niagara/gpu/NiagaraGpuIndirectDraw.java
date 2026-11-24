package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/** Produces one indexed draw command from the authoritative GPU particle count. */
public final class NiagaraGpuIndirectDraw implements AutoCloseable {
    private final NiagaraGpuComputeStage stage;
    private final GpuBuffer task;
    private final GpuBuffer command;
    private boolean closed;

    public NiagaraGpuIndirectDraw(String name, NiagaraGpuPrograms programs) {
        RenderSystem.assertOnRenderThread();
        stage = programs.drawIndirect();
        GpuBuffer taskBuffer = null, commandBuffer = null;
        try {
            taskBuffer = RenderSystem.getDevice().createBuffer(() -> name + ".DrawTask",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), 5L * Integer.BYTES);
            commandBuffer = RenderSystem.getDevice().createBuffer(() -> name + ".DrawCommand",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDIRECT_PARAMETERS
                            | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER),
                    5L * Integer.BYTES);
            task = taskBuffer;
            command = commandBuffer;
        } catch (RuntimeException | Error error) {
            if (commandBuffer != null) commandBuffer.close();
            if (taskBuffer != null) taskBuffer.close();
            throw error;
        }
    }

    public GpuBufferSlice prepare(CommandEncoder encoder, NiagaraGpuParticleView particles, int indicesPerInstance,
                                  int firstIndex, @Nullable GpuBufferSlice culledCounts, int culledOffset,
                                  boolean stereo, boolean postOpaque) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara indirect draw is closed");
        if (indicesPerInstance < 0 || firstIndex < 0 || culledCounts != null
                && (culledOffset < 0 || ((long)culledOffset + 1) * Integer.BYTES > culledCounts.length()))
            throw new IllegalArgumentException("Invalid Niagara indirect draw task");
        int flags = (culledCounts == null ? 0 : 1) | (stereo ? 2 : 0) | (postOpaque ? 4 : 0);
        int countOffset = culledCounts == null ? particles.currentCountOffset() : culledOffset;
        GpuBufferSlice taskUpload;
        try (var upload = encoder.transientMemory().allocateGpuMapped(5L * Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            upload.data().order(ByteOrder.nativeOrder()).putInt(0).putInt(countOffset)
                    .putInt(indicesPerInstance).putInt(firstIndex).putInt(flags);
            taskUpload = upload.slice();
        }
        encoder.copyToBuffer(taskUpload, task.slice());
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice slice = switch (storage.name()) {
                case "TaskInfos" -> task.slice();
                case "CulledInstanceCounts" -> culledCounts == null ? particles.countBuffer() : culledCounts;
                case "RWInstanceCounts" -> particles.countBuffer();
                case "RWDrawIndirectArgs" -> command.slice();
                default -> throw new IllegalArgumentException("Unknown Niagara indirect draw buffer " + storage.name());
            };
            bindings.storage(storage.name(), slice, storage.access());
        }
        byte[] taskCount = ByteBuffer.allocate(4 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).putInt(1).putInt(0).putInt(1).array();
        stage.dispatch(encoder, bindings, name -> name.equals("TaskCount") ? taskCount : null, 1, 1, 1);
        return command.slice();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        command.close();
        task.close();
    }
}
