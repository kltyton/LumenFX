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

/** GPU free-ID list and per-dataset ID-to-index tables. */
final class NiagaraGpuPersistentIds implements AutoCloseable {
    private static final int CHUNK = 1024;
    private final String name;
    private final NiagaraGpuPrograms programs;
    private final GpuBuffer[] tables = new GpuBuffer[NiagaraGpuParticleBuffers.DATA_BUFFERS];
    private final int[] tableCapacity = new int[NiagaraGpuParticleBuffers.DATA_BUFFERS];
    private final GpuBuffer freeCount;
    private @Nullable GpuBuffer freeIds;
    private int allocated;
    private boolean closed;

    NiagaraGpuPersistentIds(String name, NiagaraGpuPrograms programs) {
        this.name = name;
        this.programs = programs;
        freeCount = RenderSystem.getDevice().createBuffer(() -> name + ".FreeIDCount",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), Integer.BYTES);
    }

    void ensure(CommandEncoder encoder, int particleCapacity) {
        if (closed || particleCapacity < 0) throw new IllegalArgumentException("Invalid Niagara persistent ID capacity");
        int required = Math.toIntExact(Math.multiplyExact(Math.ceilDiv(Math.addExact((long) particleCapacity, 1), CHUNK), CHUNK));
        if (required <= allocated) return;
        long bytes = Math.multiplyExact((long) required, Integer.BYTES);
        if (bytes > programs.runtime().maxStorageBufferBytes() || bytes > programs.runtime().maxTexelBufferElements() * Integer.BYTES)
            throw new IllegalArgumentException("Niagara persistent IDs exceed device buffer limit");
        if (required / 64 > programs.runtime().maxWorkGroups(0))
            throw new IllegalArgumentException("Niagara persistent ID initialization exceeds dispatch limit");
        GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> name + ".FreeIDs",
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
        try {
            NiagaraGpuComputeStage shader = programs.persistentIds("init");
            ComputeBindings.Builder resources = ComputeBindings.builder();
            for (ShaderBinding binding : shader.shader().bindings()) {
                if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
                GpuBuffer buffer = switch (storage.name()) {
                    case "RWNewBuffer" -> next;
                    case "ExistingBuffer" -> freeIds == null ? programs.dummyBuffer() : freeIds;
                    default -> throw new IllegalArgumentException("Unknown Niagara ID initialization buffer " + storage.name());
                };
                resources.storage(storage.name(), buffer.slice(), storage.access());
            }
            int oldSize = allocated, newSize = required - allocated;
            shader.dispatch(encoder, resources, name -> switch (name.substring(name.lastIndexOf('.') + 1)) {
                case "NumNewElements" -> word(newSize);
                case "NumExistingElements" -> word(oldSize);
                default -> null;
            }, required, 1, 1);
        } catch (RuntimeException | Error error) {
            next.close();
            throw error;
        }
        if (freeIds != null) freeIds.close();
        freeIds = next;
        allocated = required;
    }

    void beginWrite(int bufferIndex) {
        int index = java.util.Objects.checkIndex(bufferIndex, tables.length);
        if (allocated == 0) throw new IllegalStateException("Niagara persistent ID list is not allocated");
        if (tableCapacity[index] < allocated) {
            long bytes = Math.multiplyExact((long) allocated, Integer.BYTES);
            GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> name + ".IDToIndex" + index,
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST), bytes);
            if (tables[index] != null) tables[index].close();
            tables[index] = replacement;
            tableCapacity[index] = allocated;
        }
        programs.runtime().clearBuffer(tables[index].slice(), -1);
    }

    void bind(ShaderBinding.StorageBuffer binding, ComputeBindings.Builder resources, int outputIndex) {
        GpuBuffer buffer = switch (binding.name()) {
            case "FreeIDList" -> freeIds;
            case "RWIDToIndexTable" -> outputIndex < 0 ? programs.dummyBuffer() : tables[outputIndex];
            default -> throw new IllegalArgumentException("Unknown Niagara ID buffer " + binding.name());
        };
        if (buffer == null) throw new IllegalStateException("Missing Niagara GPU persistent ID buffer " + binding.name());
        resources.storage(binding.name(), buffer.slice(), binding.access());
    }

    @Nullable GpuBuffer currentTable(int index) { return tables[java.util.Objects.checkIndex(index, tables.length)]; }

    void copyTable(CommandEncoder encoder, int source, int target) {
        var previous = tables[source];
        if (previous == null) return;
        beginWrite(target);
        encoder.copyToBuffer(previous.slice(), tables[target].slice(0, previous.size()));
    }

    void uploadCacheTable(CommandEncoder encoder, int target, int[] table) {
        if (table.length == 0) return;
        long length = (long)table.length * Integer.BYTES;
        GpuBufferSlice upload;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(length, Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer().put(table);
            upload = mapped.slice();
        }
        encoder.copyToBuffer(upload, tables[target].slice(0, length));
    }
    @Nullable GpuBuffer freeIds() { return freeIds; }

    void swapTables(int a, int b) {
        GpuBuffer table = tables[a]; tables[a] = tables[b]; tables[b] = table;
        int capacity = tableCapacity[a]; tableCapacity[a] = tableCapacity[b]; tableCapacity[b] = capacity;
    }

    void releaseTable(int index) {
        if (tables[index] != null) { tables[index].close(); tables[index] = null; }
        tableCapacity[index] = 0;
    }

    void reset() {
        if (closed) throw new IllegalStateException("Niagara persistent IDs are closed");
        if (freeIds != null) { freeIds.close(); freeIds = null; }
        for (int i = 0; i < tables.length; i++) {
            if (tables[i] != null) { tables[i].close(); tables[i] = null; }
            tableCapacity[i] = 0;
        }
        allocated = 0;
    }

    void compact(CommandEncoder encoder, int currentIndex) {
        GpuBuffer table = tables[java.util.Objects.checkIndex(currentIndex, tables.length)];
        if (table == null || freeIds == null) throw new IllegalStateException("Missing Niagara persistent ID table");
        if (allocated / 128 > programs.runtime().maxWorkGroups(0))
            throw new IllegalArgumentException("Niagara persistent ID compaction exceeds dispatch limit");
        programs.runtime().clearBuffer(freeCount.slice(), 0);
        NiagaraGpuComputeStage shader = programs.persistentIds("compact");
        ComputeBindings.Builder resources = ComputeBindings.builder();
        for (ShaderBinding binding : shader.shader().bindings()) {
            if (!(binding instanceof ShaderBinding.StorageBuffer storage)) continue;
            GpuBuffer buffer = switch (storage.name()) {
                case "IDToIndexTable" -> table;
                case "RWFreeIDList" -> freeIds;
                case "RWFreeIDListSizes" -> freeCount;
                default -> throw new IllegalArgumentException("Unknown Niagara ID compaction buffer " + storage.name());
            };
            resources.storage(storage.name(), buffer.slice(), storage.access());
        }
        shader.dispatch(encoder, resources, name -> name.endsWith("FreeIDListIndex") ? word(0) : null, allocated, 1, 1);
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (freeIds != null) freeIds.close();
        for (GpuBuffer table : tables) if (table != null) table.close();
        freeCount.close();
    }
}
