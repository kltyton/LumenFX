package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import org.jspecify.annotations.Nullable;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** System-owned counters shared by CPU and GPU Niagara data-interface instances. */
public final class NiagaraGpuCounterRegistry implements AutoCloseable {
    private static final class Entry implements AutoCloseable {
        final int offset, initial;
        final NiagaraInterfaces.CounterState source;
        final NiagaraGpuCounterReadback readback;
        long uploadedRevision;
        boolean initialized, touched;

        Entry(String id, int offset, int initial, NiagaraInterfaces.CounterState source) {
            this.offset = offset; this.initial = initial; this.source = source;
            readback = new NiagaraGpuCounterReadback(id, source);
        }
        @Override public void close() { readback.close(); }
    }

    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuCountArena counts;
    private final NiagaraInterfaces interfaces;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private NiagaraGpuIndirectDispatch indirect;
    private boolean closed;

    public NiagaraGpuCounterRegistry(NiagaraGpuPrograms programs, NiagaraGpuCountArena counts, NiagaraInterfaces interfaces) {
        this.programs = programs; this.counts = counts; this.interfaces = interfaces;
    }

    public void register(JsonObject binding) {
        if (closed || !binding.has("interface_id") || !binding.has("simple_counter"))
            throw new IllegalArgumentException("Niagara GPU counter lacks its source definition");
        String id = binding.get("interface_id").getAsString();
        JsonObject definition = binding.getAsJsonObject("simple_counter");
        Entry existing = entries.get(id);
        if (existing != null) {
            if (existing.initial != definition.get("initial").getAsInt()
                    || !existing.source.syncMode().equals(definition.get("gpu_sync_mode").getAsString()))
                throw new IllegalArgumentException("Inconsistent Niagara counter source " + id);
            return;
        }
        NiagaraInterfaces.CounterState source = interfaces.counter(id, definition);
        entries.put(id, new Entry(id, counts.interfaceOffset(id), definition.get("initial").getAsInt(), source));
    }

    public void prepare(CommandEncoder encoder, String id) {
        prepare(encoder, id, null);
    }

    public void prepare(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable CounterSnapshot input) {
        prepare(encoder, id, input, false);
    }

    private void prepare(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable CounterSnapshot input, boolean cached) {
        Entry entry = entry(id);
        boolean toGpu = cached || entry.source.syncMode().equals("SyncCpuToGpu") || entry.source.syncMode().equals("SyncBoth");
        long revision = input == null ? entry.source.revision() : input.revision();
        if (!entry.initialized || toGpu && entry.uploadedRevision != revision) {
            int value = toGpu ? (input == null ? entry.source.get() : input.value()) : entry.initial;
            GpuBufferSlice counterUpload;
            try (var upload = encoder.transientMemory().allocateGpuMapped(Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                upload.data().order(ByteOrder.nativeOrder()).putInt(value);
                counterUpload = upload.slice();
            }
            encoder.copyToBuffer(counterUpload, counts.buffer().slice((long)entry.offset * Integer.BYTES, Integer.BYTES));
            entry.uploadedRevision = revision;
            entry.initialized = true;
        }
        if (!cached) entry.touched = true;
    }

    public void prepareSelected(CommandEncoder encoder, String id) {
        prepareSelected(encoder, id, null);
    }

    public void prepareSelected(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable CounterSnapshot input) {
        prepareSelected(encoder, id, input, false);
    }

    public void prepareCached(CommandEncoder encoder, String id, NiagaraInterfaces.CounterSnapshot input) {
        prepareSelected(encoder, id, input, true);
    }

    public void resumeCached(CommandEncoder encoder, String id, NiagaraInterfaces.CounterSnapshot input, long liveRevision) {
        prepareCached(encoder, id, input);
        entry(id).uploadedRevision = liveRevision;
    }

    private void prepareSelected(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable CounterSnapshot input, boolean cached) {
        if (closed) throw new IllegalStateException("Niagara GPU counters have been released");
        entries.computeIfAbsent(id, ignored -> {
            var source = interfaces.counter(id);
            return new Entry(id, counts.interfaceOffset(id), source.initial(), source);
        });
        prepare(encoder, id, input, cached);
    }

    public int offset(String id) { return entry(id).offset; }

    public CompletableFuture<Integer> captureCache(CommandEncoder encoder, @Nullable ClientLevel world, String id, int cpuValue) {
        var entry = entries.get(id);
        if (entry == null || !entry.initialized) return CompletableFuture.completedFuture(cpuValue);
        return NiagaraGpuCacheReadbacks.counter(encoder, world, id, counts.buffer().slice((long)entry.offset * Integer.BYTES, Integer.BYTES));
    }

    public NiagaraGpuSimulation.InterfaceIteration iteration(CommandEncoder encoder, String id, ShaderProgram target) {
        Entry entry = entry(id);
        if (!entry.initialized) throw new IllegalStateException("Niagara GPU counter has not been prepared: " + id);
        if (indirect == null) indirect = new NiagaraGpuIndirectDispatch(programs);
        return new NiagaraGpuSimulation.InterfaceIteration(null, indirect.generate(encoder, counts.buffer(), entry.offset, target), entry.offset);
    }

    public void postFrame(CommandEncoder encoder) {
        for (Entry entry : entries.values()) {
            if (entry.touched && (entry.source.syncMode().equals("SyncGpuToCpu") || entry.source.syncMode().equals("SyncBoth")))
                entry.readback.submit(encoder, counts.buffer().slice((long)entry.offset * Integer.BYTES, Integer.BYTES));
            entry.touched = false;
        }
    }

    public void poll() { for (Entry entry : entries.values()) entry.readback.poll(); }

    public void reset() {
        if (closed) throw new IllegalStateException("Niagara GPU counters are closed");
        entries.values().forEach(Entry::close); entries.clear();
    }

    private Entry entry(String id) {
        if (closed) throw new IllegalStateException("Niagara GPU counters are closed");
        Entry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown Niagara GPU counter " + id);
        return entry;
    }

    @Override public void close() {
        if (closed) return;
        reset();
        if (indirect != null) indirect.close();
        closed = true;
    }
}
