package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds the UE eight-word indirect dispatch record from a GPU counter. */
final class NiagaraGpuIndirectDispatch implements AutoCloseable {
    private final NiagaraGpuPrograms programs;
    private final GpuBuffer info, arguments;

    NiagaraGpuIndirectDispatch(NiagaraGpuPrograms programs) {
        this.programs = programs;
        info = RenderSystem.getDevice().createBuffer(() -> "Niagara.DispatchInfo",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), 7L * Integer.BYTES);
        try {
            arguments = RenderSystem.getDevice().createBuffer(() -> "Niagara.DispatchArguments",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_INDIRECT_PARAMETERS), 8L * Integer.BYTES);
        } catch (RuntimeException | Error error) {
            info.close();
            throw error;
        }
    }

    GpuBufferSlice generate(CommandEncoder encoder, GpuBuffer counterBuffer, int counterOffset, ShaderProgram target) {
        if (counterOffset < 0) throw new IllegalArgumentException("Invalid Niagara indirect counter offset");
        GpuBufferSlice infoUpload;
        try (var upload = encoder.transientMemory().allocateGpuMapped(7L * Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            upload.data().order(ByteOrder.nativeOrder()).putInt(counterOffset).putInt(-1).putInt(-1)
                    .putInt(target.workgroupX()).putInt(target.workgroupY()).putInt(target.workgroupZ()).putInt(0);
            infoUpload = upload.slice();
        }
        encoder.copyToBuffer(infoUpload, info.slice());
        NiagaraGpuComputeStage shader = programs.persistentIds("dispatch_indirect");
        ComputeBindings.Builder resources = ComputeBindings.builder();
        for (var binding : shader.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBuffer buffer = switch (storage.name()) {
                case "DispatchInfos" -> info;
                case "InstanceCounts" -> counterBuffer;
                case "RWDispatchIndirectArgs" -> arguments;
                default -> throw new IllegalArgumentException("Unknown Niagara indirect dispatch buffer " + storage.name());
            };
            resources.storage(storage.name(), buffer.slice(), storage.access());
        }
        shader.dispatch(encoder, resources, qualified -> {
            String name = qualified.substring(qualified.lastIndexOf('.') + 1);
            return switch (name) {
                case "NumDispatchInfos" -> words(1);
                case "MaxGroupsPerDimension" -> words(programs.runtime().maxWorkGroups(0),
                        programs.runtime().maxWorkGroups(1), programs.runtime().maxWorkGroups(2));
                default -> null;
            };
        }, 1, 1, 1);
        return arguments.slice();
    }

    private static byte[] words(int... values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) result.putInt(value);
        return result.array();
    }

    @Override public void close() { arguments.close(); info.close(); }
}
