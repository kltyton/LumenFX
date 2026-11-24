package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Unreal's GPU bitonic local-sort and merge sequence for particle keys and indices. */
public final class NiagaraGpuBitonicSort implements AutoCloseable {
    private static final int BLOCK_SIZE = 1024;
    private static final int THREADS = 512;
    private final NiagaraGpuPrograms programs;
    private final String name;
    private final GpuBuffer[] keys = new GpuBuffer[2];
    private final GpuBuffer[] values = new GpuBuffer[2];
    private int capacity;
    private int count;
    private int padded;
    private boolean closed;

    public NiagaraGpuBitonicSort(String name, NiagaraGpuPrograms programs) {
        this.name = name;
        this.programs = programs;
    }

    /** Allocates output for key generation; the returned buffers are the initial sort input. */
    public void prepare(int particleCount) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU sort is closed");
        if (particleCount < 0) throw new IllegalArgumentException("Negative Niagara sort count");
        count = particleCount;
        if (particleCount <= 1) padded = Math.max(1, particleCount);
        else {
            long next = 1L << (64 - Long.numberOfLeadingZeros((long)particleCount - 1));
            padded = Math.toIntExact(next);
        }
        int required = Math.max(BLOCK_SIZE, padded);
        if (required <= capacity) return;
        long bytes = Math.multiplyExact((long)required, Integer.BYTES);
        long maximum = Math.min(programs.runtime().maxStorageBufferBytes(),
                programs.runtime().maxTexelBufferElements() * Integer.BYTES);
        if (bytes > maximum) throw new IllegalArgumentException("Niagara GPU sort buffer exceeds device limit");
        GpuBuffer[] newKeys = new GpuBuffer[2], newValues = new GpuBuffer[2];
        try {
            for (int i = 0; i < 2; i++) {
                String keyLabel = name + ".SortKeys" + i, valueLabel = name + ".SortIndices" + i;
                newKeys[i] = RenderSystem.getDevice().createBuffer(() -> keyLabel,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), bytes);
                newValues[i] = RenderSystem.getDevice().createBuffer(() -> valueLabel,
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC
                                | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), bytes);
            }
        } catch (RuntimeException | Error error) {
            for (GpuBuffer buffer : newKeys) if (buffer != null) buffer.close();
            for (GpuBuffer buffer : newValues) if (buffer != null) buffer.close();
            throw error;
        }
        for (int i = 0; i < 2; i++) {
            if (keys[i] != null) keys[i].close();
            if (values[i] != null) values[i].close();
            keys[i] = newKeys[i]; values[i] = newValues[i];
        }
        capacity = required;
    }

    public GpuBuffer inputKeys() { requirePrepared(); return keys[0]; }
    public GpuBuffer inputIndices() { requirePrepared(); return values[0]; }

    public GpuBuffer sort(CommandEncoder encoder) {
        RenderSystem.assertOnRenderThread();
        requirePrepared();
        if (count <= 1) return values[0];
        int current = 0;
        int localGroups = Math.max(1, padded / BLOCK_SIZE);
        local(encoder, "local_sort", current, current ^ 1, 0, 0, localGroups);
        current ^= 1;
        int lastStage = 31 - Integer.numberOfLeadingZeros(padded);
        for (int stage = 11; stage <= lastStage; stage++) {
            int stageSize = 1 << stage;
            for (int step = stage; step >= 1; step--) {
                int stepSize = 1 << step;
                if (stepSize <= BLOCK_SIZE) {
                    local(encoder, "local_merge", current, current ^ 1, stageSize, stepSize, localGroups);
                    current ^= 1;
                    break;
                }
                int groups = Math.max(1, Math.ceilDiv(padded / 2, THREADS));
                global(encoder, current, stageSize, stepSize, groups);
            }
        }
        return values[current];
    }

    private void local(CommandEncoder encoder, String pass, int source, int destination,
                       int stageSize, int stepSize, int groups) {
        var shader = programs.bitonic(pass);
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : shader.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBuffer buffer = switch (storage.name()) {
                case "InKeys" -> keys[source];
                case "InValues" -> values[source];
                case "OutKeys" -> keys[destination];
                case "OutValues" -> values[destination];
                default -> throw new IllegalArgumentException("Unknown Niagara bitonic buffer " + storage.name());
            };
            bindings.storage(storage.name(), buffer.slice(), storage.access());
        }
        dispatch(encoder, shader, bindings, stageSize, stepSize, groups);
    }

    private void global(CommandEncoder encoder, int current, int stageSize, int stepSize, int groups) {
        var shader = programs.bitonic("global_merge");
        ComputeBindings.Builder bindings = ComputeBindings.builder();
        for (ShaderBinding binding : shader.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBuffer buffer = switch (storage.name()) {
                case "InOutKeys" -> keys[current];
                case "InOutValues" -> values[current];
                default -> throw new IllegalArgumentException("Unknown Niagara bitonic merge buffer " + storage.name());
            };
            bindings.storage(storage.name(), buffer.slice(), storage.access());
        }
        dispatch(encoder, shader, bindings, stageSize, stepSize, groups);
    }

    private void dispatch(CommandEncoder encoder, NiagaraGpuComputeStage shader, ComputeBindings.Builder bindings,
                          int stageSize, int stepSize, int groups) {
        if (groups > programs.runtime().maxWorkGroups(0))
            throw new IllegalArgumentException("Niagara GPU bitonic dispatch exceeds device limit");
        shader.dispatch(encoder, bindings, field -> switch (field) {
            case "BitonicSortUB.BitonicSort_Count", "BitonicSort_Count" -> word(count);
            case "BitonicSortUB.BitonicSort_PaddedCount", "BitonicSort_PaddedCount" -> word(padded);
            case "BitonicSortUB.BitonicSort_StageBlockSize", "BitonicSort_StageBlockSize" -> word(stageSize);
            case "BitonicSortUB.BitonicSort_StepBlockSize", "BitonicSort_StepBlockSize" -> word(stepSize);
            default -> null;
        }, Math.multiplyExact(groups, THREADS), 1, 1);
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private void requirePrepared() {
        if (closed || keys[0] == null) throw new IllegalStateException("Niagara GPU sort has not been prepared");
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (GpuBuffer buffer : keys) if (buffer != null) buffer.close();
        for (GpuBuffer buffer : values) if (buffer != null) buffer.close();
    }
}
