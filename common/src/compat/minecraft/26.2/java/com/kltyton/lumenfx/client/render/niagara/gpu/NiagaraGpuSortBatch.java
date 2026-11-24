package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Sorts a contiguous renderer batch and retains its GPU-owned visibility counts. */
public final class NiagaraGpuSortBatch implements AutoCloseable {
    private static final int BITONIC_AUTO_THRESHOLD = 1 << 17;
    private final String name;
    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuSortKeyGenerator generator;
    private final NiagaraGpuBitonicSort bitonic;
    private final NiagaraGpuRadixSort radix;
    private @Nullable GpuBuffer culledCounts;
    private int culledCapacity;
    private boolean closed;

    public NiagaraGpuSortBatch(String name, NiagaraGpuPrograms programs) {
        RenderSystem.assertOnRenderThread();
        this.name = name;
        this.programs = programs;
        generator = new NiagaraGpuSortKeyGenerator(programs);
        bitonic = new NiagaraGpuBitonicSort(name, programs);
        radix = new NiagaraGpuRadixSort(name, programs);
    }

    public record Result(GpuBuffer indices, @Nullable GpuBufferSlice culledCounts) { }

    public Result sort(CommandEncoder encoder, List<NiagaraGpuSortKeyGenerator.Input> inputs) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU sort batch is closed");
        if (inputs.isEmpty()) throw new IllegalArgumentException("Empty Niagara sort batch");
        int count = 0, cullSlots = 0;
        boolean precision = inputs.getFirst().highPrecision();
        Set<Integer> usedCullSlots = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            if (input.batchElements() != inputs.size() || input.elementIndex() != i
                    || input.outputOffset() != count || input.highPrecision() != precision)
                throw new IllegalArgumentException("Inconsistent Niagara sort batch allocation");
            count = Math.addExact(count, input.particleCount());
            if (input.culling() != null) {
                if (!usedCullSlots.add(input.culledCountOffset()))
                    throw new IllegalArgumentException("Duplicate Niagara cull count offset");
                cullSlots = Math.max(cullSlots, Math.addExact(input.culledCountOffset(), 1));
            }
        }
        GpuBufferSlice cullBuffer = null;
        if (cullSlots > 0) {
            ensureCullCapacity(cullSlots);
            cullBuffer = culledCounts.slice();
            programs.runtime().clearBuffer(cullBuffer, 0);
        }
        boolean useBitonic = count <= BITONIC_AUTO_THRESHOLD;
        GpuBuffer keys, indices;
        if (useBitonic) {
            bitonic.prepare(count);
            keys = bitonic.inputKeys();
            indices = bitonic.inputIndices();
        } else {
            radix.prepare(count);
            keys = radix.inputKeys();
            indices = radix.inputIndices();
        }
        for (var input : inputs) generator.generate(encoder, input, keys.slice(), indices.slice(), cullBuffer);
        var layout = NiagaraGpuSortKeyGenerator.KeyLayout.forBatch(inputs.size(), precision);
        int bits = inputs.size() == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(inputs.size() - 1);
        int elementMask = (1 << bits) - 1;
        if (precision) elementMask = Math.max(elementMask, 1);
        int mask = (elementMask << layout.elementShift()) | layout.sortMask();
        GpuBuffer sorted = useBitonic ? bitonic.sort(encoder) : radix.sort(encoder, mask);
        return new Result(sorted, cullBuffer);
    }

    private void ensureCullCapacity(int slots) {
        if (slots <= culledCapacity) return;
        long bytes = Math.multiplyExact((long) slots, Integer.BYTES);
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara cull count buffer exceeds device limit");
        GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> name + ".CulledCounts",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
        if (culledCounts != null) culledCounts.close();
        culledCounts = next;
        culledCapacity = slots;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        bitonic.close();
        radix.close();
        if (culledCounts != null) culledCounts.close();
    }
}
