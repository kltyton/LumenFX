package com.kltyton.lumenfx.client.render.niagara.gpu.memory;

import com.mojang.logging.LogUtils;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer.Snapshot;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.memory.NiagaraMemoryBufferDefinition.SyncMode;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Keeps byte-address buffers shared by the shader aliases of a physical memory interface. */
public final class NiagaraGpuMemoryBuffers implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final class Entry implements AutoCloseable {
        final GpuBuffer data;
        Snapshot snapshot;
        boolean touched;
        Entry(GpuBuffer data, Snapshot snapshot) { this.data = data; this.snapshot = snapshot; }
        @Override public void close() { data.close(); }
    }

    private final NiagaraGpuPrograms programs;
    private final Function<String, NiagaraMemoryBuffer> cpu;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public NiagaraGpuMemoryBuffers(NiagaraGpuPrograms programs, Function<String, NiagaraMemoryBuffer> cpu) {
        this.programs = programs; this.cpu = cpu;
    }

    public void prepare(CommandEncoder encoder, String id, Snapshot snapshot) {
        var entry = entries.get(id);
        if (entry == null || entry.snapshot.revision() != snapshot.revision() || entry.snapshot.elements() != snapshot.elements()) {
            entry = replace(encoder, id, snapshot);
        }
        entry.touched = true;
    }

    private Entry replace(CommandEncoder encoder, String id, Snapshot snapshot) {
        int length = Math.addExact(snapshot.elements(), 4);
        long bytes = (long)length * Integer.BYTES;
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara memory buffer exceeds GPU storage capacity: " + id);
        var target = RenderSystem.getDevice().createBuffer(() -> id + ".NiagaraMemory",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
        try {
            int[] words = snapshot.words();
            if (words.length == 0) {
                programs.runtime().clearBuffer(target.slice(), snapshot.pendingClear() == null ? 0 : snapshot.pendingClear());
            } else {
                if (words.length != length) throw new IllegalArgumentException("Niagara memory publication has the wrong number of words");
                GpuBufferSlice upload;
                try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                    mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().put(words);
                    upload = mapped.slice();
                }
                encoder.copyToBuffer(upload, target.slice());
            }
            var entry = new Entry(target, snapshot);
            var previous = entries.put(id, entry);
            if (previous != null) previous.close();
            return entry;
        } catch (RuntimeException | Error failure) {
            target.close();
            throw failure;
        }
    }

    public GpuBufferSlice buffer(String id) {
        return Objects.requireNonNull(entries.get(id), "Missing prepared Niagara memory buffer: " + id).data.slice();
    }

    public void postFrame(CommandEncoder encoder, @Nullable ClientLevel world) {
        for (var value : entries.entrySet()) {
            var entry = value.getValue();
            if (!entry.touched) continue;
            entry.touched = false;
            if (!entry.snapshot.sync().toCpu() || entry.snapshot.elements() == 0) continue;
            String id = value.getKey();
            var state = cpu.apply(id);
            long cacheEpoch = state.cacheEpoch();
            NiagaraGpuCacheReadbacks.array(encoder, world, id, null, entry.data.slice(), 1, entry.snapshot.elements() + 4)
                    .whenComplete((result, failure) -> {
                        if (failure == null) state.applyGpu(result.words(), cacheEpoch);
                        else if (!(failure instanceof CancellationException)) LOGGER.error("Niagara memory readback failed for {}", id, failure);
                    });
        }
    }

    public CompletableFuture<ArrayFrame> captureCache(CommandEncoder encoder, @Nullable ClientLevel world, String id) {
        var entry = entries.get(id);
        if (entry == null || entry.snapshot.elements() == 0) return CompletableFuture.completedFuture(new ArrayFrame(false, new int[0]));
        return NiagaraGpuCacheReadbacks.array(encoder, world, id, null, entry.data.slice(), 1, entry.snapshot.elements());
    }

    public void restoreCache(CommandEncoder encoder, String id, int[] words, long revision) {
        var entry = entries.get(id);
        if (entry != null && entry.snapshot.revision() == revision) return;
        replace(encoder, id, new Snapshot(words.length, revision, Arrays.copyOf(words, words.length + 4), null, SyncMode.None));
    }

    public void resumeCache(CommandEncoder encoder, String id, int[] words, long cacheRevision, long liveRevision, SyncMode sync) {
        restoreCache(encoder, id, words, cacheRevision);
        Entry entry = entries.get(id);
        entry.snapshot = new Snapshot(words.length, liveRevision, new int[0], null, sync);
    }

    @Override public void close() {
        entries.values().forEach(Entry::close);
        entries.clear();
    }
}
