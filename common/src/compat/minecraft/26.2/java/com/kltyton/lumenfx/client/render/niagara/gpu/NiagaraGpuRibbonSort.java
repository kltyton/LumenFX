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

/** Ribbon ID and link-order sorting over the live GPU particle dataset. */
public final class NiagaraGpuRibbonSort implements AutoCloseable {
    public enum IdType { NONE, NIAGARA_ID, INTEGER }

    private final String name;
    private final NiagaraGpuPrograms programs;
    private final GpuBuffer[] indices = new GpuBuffer[2];
    private int capacity;
    private boolean closed;

    public NiagaraGpuRibbonSort(String name, NiagaraGpuPrograms programs) {
        this.name = name;
        this.programs = programs;
    }

    public GpuBuffer sort(CommandEncoder encoder, NiagaraGpuParticleView particles, int executableInstances,
                          IdType idType, int ribbonIdOffset, int linkOffset, boolean linkIsFloat) {
        RenderSystem.assertOnRenderThread();
        if (closed || executableInstances < 2 || executableInstances >= particles.current().stride())
            throw new IllegalArgumentException("Invalid Niagara GPU ribbon sort capacity");
        ensureCapacity(executableInstances);
        int groups = Math.ceilDiv(executableInstances, 32);
        if (groups > programs.runtime().maxWorkGroups(0))
            throw new IllegalArgumentException("Niagara ribbon sort dispatch exceeds device limit");
        int passes = groups <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(groups - 1);
        int current = passes & 1;
        String variant = switch (idType) {
            case NONE -> "single";
            case NIAGARA_ID -> "id";
            case INTEGER -> "integer";
        };
        dispatch(encoder, programs.ribbonSort("initial", variant), particles, null, indices[current],
                executableInstances, ribbonIdOffset, linkOffset, linkIsFloat, 0, groups * 32);
        int blockSize = 32;
        for (int pass = 0; pass < passes; pass++) {
            int destination = current ^ 1;
            dispatch(encoder, programs.ribbonSort("merge", variant), particles, indices[current], indices[destination],
                    executableInstances, ribbonIdOffset, linkOffset, linkIsFloat, blockSize, groups * 64);
            current = destination;
            blockSize = Math.multiplyExact(blockSize, 2);
        }
        return indices[current];
    }

    private void dispatch(CommandEncoder encoder, NiagaraGpuComputeStage stage, NiagaraGpuParticleView particles,
                          @Nullable GpuBuffer input, GpuBuffer output, int instances, int idOffset, int linkOffset,
                          boolean linkIsFloat, int blockSize, int threads) {
        NiagaraGpuDataBuffer data = particles.current();
        GpuBufferSlice dummy = programs.dummyBuffer().slice();
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBufferSlice buffer = switch (storage.name()) {
                case "NiagaraParticleDataFloat" -> slice(data.floats(), dummy);
                case "NiagaraParticleDataHalf" -> slice(data.halves(), dummy);
                case "NiagaraParticleDataInt" -> slice(data.integers(), dummy);
                case "EmitterParticleCountsBuffer" -> particles.countBuffer();
                case "SortedIndices" -> slice(input, dummy);
                case "DestinationSortedIndices" -> output.slice();
                default -> throw new IllegalArgumentException("Unknown Niagara ribbon sort buffer " + storage.name());
            };
            bindings.storage(storage.name(), buffer, storage.access());
        }
        stage.dispatch(encoder, bindings, qualified -> {
            String field = qualified.substring(qualified.lastIndexOf('.') + 1);
            return switch (field) {
                case "TotalNumParticlesDirect" -> word(instances);
                case "EmitterParticleCountsBufferOffset", "CountOffset" -> word(particles.currentCountOffset());
                case "NiagaraFloatDataStride", "NiagaraIntDataStride" -> word(data.stride());
                case "RibbonIdDataOffset" -> word(idOffset);
                case "RibbonLinkOrderDataOffset" -> word(linkOffset);
                case "RibbonLinkOrderIsFloat" -> word(linkIsFloat ? 1 : 0);
                case "MergeSortSourceBlockSize" -> word(blockSize);
                case "MergeSortDestinationBlockSize" -> word(blockSize * 2);
                default -> NiagaraGpuRibbonUniforms.commonStageUniform(field);
            };
        }, threads, 1, 1);
    }

    private void ensureCapacity(int required) {
        if (required <= capacity) return;
        long bytes = Math.multiplyExact((long) required, Integer.BYTES);
        if (bytes > Math.min(programs.runtime().maxStorageBufferBytes(),
                programs.runtime().maxTexelBufferElements() * Integer.BYTES))
            throw new IllegalArgumentException("Niagara ribbon sort buffer exceeds device limit");
        GpuBuffer[] replacement = new GpuBuffer[2];
        try {
            for (int i = 0; i < 2; i++) {
                String label = name + ".RibbonSorted" + i;
                replacement[i] = RenderSystem.getDevice().createBuffer(() -> label,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), bytes);
            }
        } catch (RuntimeException | Error error) {
            for (GpuBuffer buffer : replacement) if (buffer != null) buffer.close();
            throw error;
        }
        for (int i = 0; i < 2; i++) {
            if (indices[i] != null) indices[i].close();
            indices[i] = replacement[i];
        }
        capacity = required;
    }

    private static GpuBufferSlice slice(@Nullable GpuBuffer buffer, GpuBufferSlice fallback) {
        return buffer == null ? fallback : buffer.slice();
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (GpuBuffer buffer : indices) if (buffer != null) buffer.close();
    }
}
