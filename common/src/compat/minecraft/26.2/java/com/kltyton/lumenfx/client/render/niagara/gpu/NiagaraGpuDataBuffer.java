package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One GPU particle dataset, with the scratch instance required by Niagara output compaction. */
public final class NiagaraGpuDataBuffer implements AutoCloseable {
    private static final int THREADGROUP_ALIGNMENT = 64;
    private final int stride;
    private final @Nullable GpuBuffer floats;
    private final @Nullable GpuBuffer integers;
    private final @Nullable GpuBuffer halves;
    private boolean closed;

    public NiagaraGpuDataBuffer(String name, int maximumParticles, List<NiagaraDefinition.Attribute> attributes) {
        RenderSystem.assertOnRenderThread();
        if (maximumParticles < 0) throw new IllegalArgumentException("Negative Niagara GPU particle capacity");
        long withScratch = Math.addExact((long) maximumParticles, 1L);
        long padded = Math.multiplyExact((withScratch + THREADGROUP_ALIGNMENT - 1) / THREADGROUP_ALIGNMENT,
                THREADGROUP_ALIGNMENT);
        stride = Math.toIntExact(padded);
        int floatComponents = 0, integerComponents = 0, halfComponents = 0;
        for (var attribute : attributes) {
            int end = Math.addExact(attribute.component(), attribute.count());
            switch (attribute.type()) {
                case 0 -> floatComponents = Math.max(floatComponents, end);
                case 1 -> integerComponents = Math.max(integerComponents, end);
                case 2 -> halfComponents = Math.max(halfComponents, end);
                default -> throw new IllegalArgumentException("Unknown Niagara GPU component type " + attribute.type());
            }
        }
        GpuBuffer floatBuffer = null, intBuffer = null, halfBuffer = null;
        try {
            floatBuffer = allocate(name + ".Float", floatComponents, 4);
            intBuffer = allocate(name + ".Int", integerComponents, 4);
            halfBuffer = allocate(name + ".Half", halfComponents, 2);
            floats = floatBuffer;
            integers = intBuffer;
            halves = halfBuffer;
        } catch (RuntimeException | Error error) {
            if (halfBuffer != null) halfBuffer.close();
            if (intBuffer != null) intBuffer.close();
            if (floatBuffer != null) floatBuffer.close();
            throw error;
        }
    }

    private @Nullable GpuBuffer allocate(String name, int components, int bytesPerComponent) {
        if (components == 0) return null;
        long bytes = Math.multiplyExact(Math.multiplyExact((long) stride, components), bytesPerComponent);
        return RenderSystem.getDevice().createBuffer(() -> name,
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST
                        | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER), bytes);
    }

    public static int maximumParticleCapacity(ComputeRuntime runtime, List<NiagaraDefinition.Attribute> attributes) {
        int floats = 0, integers = 0, halves = 0;
        for (var attribute : attributes) {
            int end = Math.addExact(attribute.component(), attribute.count());
            switch (attribute.type()) {
                case 0 -> floats = Math.max(floats, end);
                case 1 -> integers = Math.max(integers, end);
                case 2 -> halves = Math.max(halves, end);
                default -> throw new IllegalArgumentException("Unknown Niagara GPU component type " + attribute.type());
            }
        }
        long bytesPerInstance = Math.max(Math.max((long) floats * 4, (long) integers * 4), (long) halves * 2);
        if (bytesPerInstance == 0) return Integer.MAX_VALUE - THREADGROUP_ALIGNMENT;
        long maxBytes = Math.min(runtime.maxStorageBufferBytes(), runtime.maxTexelBufferElements() * Integer.BYTES);
        long instances = Math.min(Integer.MAX_VALUE, maxBytes / bytesPerInstance);
        long padded = instances / THREADGROUP_ALIGNMENT * THREADGROUP_ALIGNMENT;
        if (padded < THREADGROUP_ALIGNMENT) throw new IllegalStateException("Device storage limit cannot fit one Niagara GPU group");
        return Math.toIntExact(padded - 1);
    }

    public int stride() { return stride; }
    boolean hasExpandedStorage() {
        return stride > THREADGROUP_ALIGNMENT && (floats != null || integers != null || halves != null);
    }
    public @Nullable GpuBuffer floats() { return floats; }
    public @Nullable GpuBuffer integers() { return integers; }
    public @Nullable GpuBuffer halves() { return halves; }

    void copyTo(CommandEncoder encoder, NiagaraGpuDataBuffer destination) {
        if (destination.stride != stride) throw new IllegalArgumentException("Niagara dataset copies require an equal stride");
        if (floats != null) encoder.copyToBuffer(floats.slice(), java.util.Objects.requireNonNull(destination.floats).slice());
        if (integers != null) encoder.copyToBuffer(integers.slice(), java.util.Objects.requireNonNull(destination.integers).slice());
        if (halves != null) encoder.copyToBuffer(halves.slice(), java.util.Objects.requireNonNull(destination.halves).slice());
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if (halves != null) halves.close();
        if (integers != null) integers.close();
        if (floats != null) floats.close();
    }
}
