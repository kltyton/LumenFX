package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** System-owned GPU array interfaces, shared by all emitters that bind the same source object. */
public final class NiagaraGpuArrayRegistry implements AutoCloseable {
    private static final class Entry implements AutoCloseable {
        final String id;
        boolean writable;
        final int countOffset;
        final NiagaraInterfaces.ArrayState source;
        final String type, syncMode;
        final int width;
        final NiagaraGpuArrayReadback readback;
        GpuBuffer buffer;
        int capacity;
        long uploadedVersion = Long.MIN_VALUE;

        Entry(String id, boolean writable, int countOffset, NiagaraInterfaces.ArrayState source) {
            this.id = id; this.writable = writable; this.countOffset = countOffset; this.source = source;
            type = source.type(); width = source.width(); syncMode = source.syncMode();
            readback = new NiagaraGpuArrayReadback(id, source);
        }

        @Override public void close() { readback.close(); if (buffer != null) buffer.close(); }
    }

    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuCountArena counters;
    private final NiagaraInterfaces interfaces;
    private final Set<String> writableArrays;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    public NiagaraGpuArrayRegistry(NiagaraGpuPrograms programs, NiagaraGpuCountArena counters, NiagaraInterfaces interfaces,
                                   List<NiagaraDefinition.Emitter> emitters) {
        this.programs = programs; this.counters = counters; this.interfaces = interfaces;
        java.util.Set<String> writable = new java.util.HashSet<>();
        for (var emitter : emitters) {
            if (!(emitter.simulation() instanceof NiagaraDefinition.GpuSimulation gpu)) continue;
            var bindings = gpu.metadata().getAsJsonArray("interfaces");
            if (bindings == null) continue;
            for (var element : bindings) {
                JsonObject binding = element.getAsJsonObject();
                if (binding.has("array_writable") && binding.get("array_writable").getAsBoolean()
                        && binding.has("interface_id")) writable.add(binding.get("interface_id").getAsString());
            }
        }
        writableArrays = Set.copyOf(writable);
    }

    public void register(JsonObject binding) {
        if (closed || !binding.has("interface_id") || !binding.has("array_data") || !binding.has("array_writable"))
            throw new IllegalArgumentException("Niagara GPU array binding lacks source data");
        String id = binding.get("interface_id").getAsString();
        NiagaraInterfaces.ArrayState source = interfaces.array(id,
                binding.get("resolved_parameter_name").getAsString(), binding.getAsJsonObject("array_data"));
        boolean writable = writable(source, id, binding.get("array_writable").getAsBoolean());
        Entry existing = entries.get(id);
        if (existing != null) {
            promote(existing, writable);
            return;
        }
        entries.put(id, new Entry(id, writable, counters.interfaceOffset(id), source));
    }

    public void prepareSelected(CommandEncoder encoder, String id, String expectedType, boolean writable) {
        prepareSelected(encoder, id, expectedType, writable, null);
    }

    public void prepareSelected(CommandEncoder encoder, String id, String expectedType, boolean writable,
                                NiagaraInterfaces.@Nullable ArraySnapshot input) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU arrays have been released");
        var source = interfaces.array(id);
        if (!source.type().equals(expectedType)) throw new IllegalArgumentException("Niagara GPU array class mismatch: " + id);
        Entry entry = entries.computeIfAbsent(id, ignored -> new Entry(id,
                writable(source, id, writable), counters.interfaceOffset(id), source));
        promote(entry, writable(source, id, writable));
        prepare(encoder, id, input);
    }

    public void prepareCached(CommandEncoder encoder, String id, NiagaraInterfaces.ArraySnapshot input) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU arrays have been released");
        var source = interfaces.array(id);
        entries.computeIfAbsent(id, ignored -> new Entry(id, writable(source, id, false), counters.interfaceOffset(id), source));
        prepare(encoder, id, input, true);
    }

    public void resumeCached(CommandEncoder encoder, String id, NiagaraInterfaces.ArraySnapshot input, long liveRevision) {
        prepareCached(encoder, id, input);
        entry(id).uploadedVersion = liveRevision;
    }

    private boolean writable(NiagaraInterfaces.ArrayState source, String id, boolean selectedWrite) {
        Boolean classified = source.gpuWritable();
        if (source.runtimeGpuClassification()) return classified;
        return selectedWrite || (classified == null ? writableArrays.contains(id) : classified);
    }

    private static void promote(Entry entry, boolean writable) {
        if (!writable || entry.writable) return;
        entry.writable = true;
        if (entry.buffer != null) { entry.buffer.close(); entry.buffer = null; }
        entry.capacity = 0; entry.uploadedVersion = Long.MIN_VALUE;
    }

    public void prepare(CommandEncoder encoder, String id) {
        prepare(encoder, id, null);
    }

    public void prepare(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable ArraySnapshot input) {
        prepare(encoder, id, input, false);
    }

    private void prepare(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable ArraySnapshot input, boolean cached) {
        RenderSystem.assertOnRenderThread();
        Entry entry = entry(id);
        boolean toGpu = cached || entry.syncMode.equals("SyncCpuToGpu") || entry.syncMode.equals("SyncBoth");
        if (entry.buffer != null && (!toGpu || entry.uploadedVersion == (input == null ? entry.source.version() : input.version()))) return;
        var snapshot = input == null ? entry.source.snapshot() : input;
        int capacity = entry.writable ? Math.max(Math.max(snapshot.maximum(), 0), snapshot.size()) : snapshot.size();
        long words = Math.multiplyExact((long) capacity + 1, snapshot.width());
        long bytes = Math.multiplyExact(words, Integer.BYTES);
        if (bytes > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Niagara GPU array exceeds the device limit: " + id);
        if (entry.buffer == null || capacity != entry.capacity) {
            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> id + ".Array",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), bytes);
            if (entry.buffer != null) entry.buffer.close();
            entry.buffer = replacement; entry.capacity = capacity;
        }
        int[] values = snapshot.words(), defaults = snapshot.defaults();
        if (values.length > 0) {
            GpuBufferSlice valueUpload;
            try (var upload = encoder.transientMemory().allocateGpuMapped((long) values.length * Integer.BYTES,
                    Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                ByteBuffer data = upload.data().order(ByteOrder.nativeOrder());
                for (int value : values) data.putInt(value);
                valueUpload = upload.slice();
            }
            encoder.copyToBuffer(valueUpload, entry.buffer.slice(0, (long) values.length * Integer.BYTES));
        }
        GpuBufferSlice defaultUpload;
        try (var upload = encoder.transientMemory().allocateGpuMapped((long) defaults.length * Integer.BYTES,
                Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            ByteBuffer data = upload.data().order(ByteOrder.nativeOrder());
            for (int value : defaults) data.putInt(value);
            defaultUpload = upload.slice();
        }
        encoder.copyToBuffer(defaultUpload, entry.buffer.slice((long) capacity * snapshot.width() * Integer.BYTES,
                (long) defaults.length * Integer.BYTES));
        if (entry.writable) {
            GpuBufferSlice countUpload;
            try (var upload = encoder.transientMemory().allocateGpuMapped(Integer.BYTES, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
                upload.data().order(ByteOrder.nativeOrder()).putInt(snapshot.size());
                countUpload = upload.slice();
            }
            encoder.copyToBuffer(countUpload, counters.buffer().slice((long) entry.countOffset * Integer.BYTES, Integer.BYTES));
        }
        entry.uploadedVersion = snapshot.version();
    }

    public GpuBufferSlice buffer(String id) {
        Entry entry = entry(id);
        if (entry.buffer == null) throw new IllegalStateException("Niagara GPU array has not been prepared: " + id);
        return entry.buffer.slice();
    }

    public CompletableFuture<ArrayFrame> captureCache(CommandEncoder encoder, @Nullable ClientLevel world, String id) {
        var entry = entries.get(id);
        if (entry == null || entry.buffer == null) return CompletableFuture.completedFuture(new ArrayFrame(false, new int[0]));
        var count = entry.writable ? counters.buffer().slice((long)entry.countOffset * Integer.BYTES, Integer.BYTES) : null;
        return NiagaraGpuCacheReadbacks.array(encoder, world, id, count, entry.buffer.slice(), entry.width, entry.capacity);
    }

    public byte[] parameters(String id) {
        Entry entry = entry(id);
        if (entry.buffer == null) throw new IllegalStateException("Niagara GPU array has not been prepared: " + id);
        return ByteBuffer.allocate(2 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(entry.writable ? entry.countOffset : entry.capacity)
                .putInt(entry.writable ? entry.capacity : Math.max(entry.capacity - 1, 0)).array();
    }

    public NiagaraGpuSimulation.InterfaceIteration iteration(String id) {
        Entry entry = entry(id);
        if (entry.buffer == null) throw new IllegalStateException("Niagara GPU array has not been prepared: " + id);
        return new NiagaraGpuSimulation.InterfaceIteration(new NiagaraGpuDispatchGrid.Extent(entry.capacity, 1, 1),
                null, entry.writable ? entry.countOffset : -1);
    }

    public void postSimulate(CommandEncoder encoder, String id) {
        postSimulate(encoder, id, null);
    }

    public void postSimulate(CommandEncoder encoder, String id, NiagaraInterfaces.@Nullable ArraySnapshot input) {
        Entry entry = entry(id);
        boolean toCpu = entry.syncMode.equals("SyncGpuToCpu") || entry.syncMode.equals("SyncBoth");
        if (entry.writable && toCpu && (input == null ? entry.source.usedByCpu() : input.usedByCpu()))
            entry.readback.submit(encoder, counters.buffer().slice((long) entry.countOffset * Integer.BYTES, Integer.BYTES),
                    entry.buffer.slice(), entry.width, entry.capacity, entry.type);
    }

    public void poll() {
        RenderSystem.assertOnRenderThread();
        for (Entry entry : entries.values()) entry.readback.poll();
    }

    public void reset() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara GPU array registry is closed");
        entries.values().forEach(Entry::close);
        entries.clear();
    }

    private Entry entry(String id) {
        if (closed) throw new IllegalStateException("Niagara GPU array registry is closed");
        Entry result = entries.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown Niagara GPU array " + id);
        return result;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        entries.values().forEach(Entry::close); entries.clear();
    }
}
