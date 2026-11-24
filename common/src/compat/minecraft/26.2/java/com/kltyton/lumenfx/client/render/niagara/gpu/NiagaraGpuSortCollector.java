package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** Shared renderer batching for the source sort keys and GPU visibility counts. */
public final class NiagaraGpuSortCollector implements AutoCloseable {
    public interface Inputs<T> {
        NiagaraGpuSortKeyGenerator.@Nullable Input input(T task, int batchSize, int index, int offset, int culledOffset);
    }
    public record Sorted(GpuBuffer indices, int offset, @Nullable GpuBufferSlice culledCounts, int culledOffset) { }
    private record Key(boolean highPrecision, boolean postOpaque, int page) { }
    private final NiagaraGpuPrograms programs;
    private final String name;
    private final Map<Key, NiagaraGpuSortBatch> batches = new LinkedHashMap<>();
    private boolean closed;

    public NiagaraGpuSortCollector(String name, NiagaraGpuPrograms programs) { this.name = name; this.programs = programs; }

    public <T> Map<T, Sorted> prepare(CommandEncoder encoder, List<T> tasks, Inputs<T> inputs, Predicate<T> postOpaque) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara sort collector is closed");
        Map<Key, List<T>> groups = new LinkedHashMap<>();
        Map<T, NiagaraGpuSortKeyGenerator.Input> planned = new IdentityHashMap<>();
        var platform = NiagaraPlatforms.snapshot();
        for (T task : tasks) {
            var input = inputs.input(task, 1, 0, 0, 0);
            if (input != null && platform.canSortOrCullParticlesOnGpu(input.sortMode() != 0, input.culling() != null)) {
                planned.put(task, input);
                groups.computeIfAbsent(new Key(input.highPrecision(), postOpaque.test(task), 0), key -> new ArrayList<>()).add(task);
            }
        }
        Map<T, Sorted> sorted = new IdentityHashMap<>();
        long bytes = Math.min(programs.runtime().maxStorageBufferBytes(), programs.runtime().maxTexelBufferElements() * Integer.BYTES);
        int maximum = (int)Math.min(Integer.MAX_VALUE, bytes / Integer.BYTES);
        // Small batches use a bitonic buffer rounded to a power of two.
        if (maximum < 1 << 17) maximum = Integer.highestOneBit(maximum);
        for (var group : groups.entrySet()) {
            List<T> members = group.getValue();
            for (int first = 0, page = 0; first < members.size(); page++) {
                int last = first, count = 0;
                while (last < members.size() && last - first < 65536) {
                    int particles = planned.get(members.get(last)).particleCount();
                    if (particles > maximum) throw new IllegalArgumentException("One Niagara renderer sort dataset exceeds the device buffer limit");
                    if ((long)count + particles > maximum && last != first) break;
                    count = Math.addExact(count, particles);
                    last++;
                }
                List<NiagaraGpuSortKeyGenerator.Input> data = new ArrayList<>(last - first);
                int offset = 0;
                for (int index = first; index < last; index++) {
                    var input = inputs.input(members.get(index), last - first, index - first, offset, index - first);
                    if (input == null) throw new IllegalStateException("Niagara renderer inputs changed during frame preparation");
                    data.add(input); offset = Math.addExact(offset, input.particleCount());
                }
                var key = new Key(group.getKey().highPrecision(), group.getKey().postOpaque(), page);
                var batch = batches.computeIfAbsent(key, value -> new NiagaraGpuSortBatch(
                        name + "." + value.highPrecision() + "." + value.postOpaque() + "." + value.page(), programs));
                var result = batch.sort(encoder, data);
                for (int index = first; index < last; index++) {
                    var input = data.get(index - first);
                    sorted.put(members.get(index), new Sorted(result.indices(), input.outputOffset(),
                            input.culling() == null ? null : result.culledCounts(), input.culledCountOffset()));
                }
                first = last;
            }
        }
        return sorted;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        batches.values().forEach(NiagaraGpuSortBatch::close);
        batches.clear();
    }
}
