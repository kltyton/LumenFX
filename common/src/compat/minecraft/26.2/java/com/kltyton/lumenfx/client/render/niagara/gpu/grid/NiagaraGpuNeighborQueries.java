package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborQuery.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One GPU counting-sort layout per physical Neighbor Query instance. */
public final class NiagaraGpuNeighborQueries implements AutoCloseable {
    enum Field {
        CellCount, CellOffset, ParticleList, AcquireTagList, OutputCellId, OutputParticleIdIndex, OutputAcquireTag;
        boolean writable() { return this == OutputCellId || this == OutputParticleIdIndex || this == OutputAcquireTag; }
    }

    static final class Grid {
        final String id;
        Snapshot requested;
        @Nullable Snapshot allocated;
        int capacity, slots;
        Map<Field, GpuBuffer> buffers = Map.of();
        Grid(String id, Snapshot requested) { this.id = id; this.requested = requested; }
    }

    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuNeighborQuerySort sort;
    private final Map<String, Grid> grids = new HashMap<>();
    private final Map<Field, GpuBuffer> emptyWrites = new EnumMap<>(Field.class);

    public NiagaraGpuNeighborQueries(NiagaraGpuPrograms programs) {
        this.programs = programs;
        sort = new NiagaraGpuNeighborQuerySort(programs);
    }

    Grid prepare(String id, Snapshot requested) {
        var grid = grids.computeIfAbsent(id, key -> new Grid(key, requested));
        grid.requested = requested;
        return grid;
    }

    void beginOutput(Grid grid, int particles, long maximumCells) {
        var configuration = grid.requested;
        long requiredSlots = (long)particles * configuration.slotsPerParticle();
        long deviceWords = programs.runtime().maxStorageBufferBytes() / Integer.BYTES;
        if (requiredSlots > Math.min(Integer.MAX_VALUE, deviceWords))
            throw new IllegalArgumentException("Niagara Neighbor Query slot count exceeds GPU capacity: " + grid.id);
        grid.slots = (int)requiredSlots;
        if ((!configuration.equals(grid.allocated) || grid.slots > grid.capacity) && configuration.cells() <= maximumCells) {
            int capacity = Math.max(1, grid.slots);
            var next = allocate(grid.id, configuration, capacity);
            var old = grid.buffers;
            grid.buffers = next;
            grid.allocated = configuration;
            grid.capacity = capacity;
            old.values().forEach(GpuBuffer::close);
        }
        resetSlots(grid);
        clear(grid, Field.CellCount, 0);
        clear(grid, Field.CellOffset, 0);
    }

    private Map<Field, GpuBuffer> allocate(String id, Snapshot configuration, int capacity) {
        var result = new EnumMap<Field, GpuBuffer>(Field.class);
        try {
            create(result, id, Field.OutputCellId, capacity);
            create(result, id, Field.CellCount, configuration.cells());
            if (!configuration.countOnly()) {
                create(result, id, Field.CellOffset, configuration.cells());
                create(result, id, Field.ParticleList, capacity);
                if (configuration.persistentIds()) {
                    create(result, id, Field.OutputParticleIdIndex, capacity);
                    create(result, id, Field.OutputAcquireTag, capacity);
                    create(result, id, Field.AcquireTagList, capacity);
                }
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.values().forEach(GpuBuffer::close);
            throw failure;
        }
    }

    private static void create(Map<Field, GpuBuffer> buffers, String id, Field field, int count) {
        buffers.put(field, NiagaraGpuNeighborQuerySort.allocate(id + "." + field, count));
    }

    void resetSlots(Grid grid) {
        clear(grid, Field.OutputCellId, -1);
        clear(grid, Field.OutputParticleIdIndex, 0);
        clear(grid, Field.OutputAcquireTag, 0);
    }

    private void clear(Grid grid, Field field, int value) {
        var buffer = grid.buffers.get(field);
        if (buffer != null) programs.runtime().clearBuffer(buffer.slice(), value);
    }

    void finishOutput(Grid grid, CommandEncoder encoder) {
        var allocated = grid.allocated;
        if (allocated == null) return;
        int slots = Math.min(grid.slots, grid.capacity);
        var keys = slice(grid, Field.OutputCellId, true);
        var counts = slice(grid, Field.CellCount, false);
        sort.histogram(encoder, keys, counts, slots);
        if (!allocated.countOnly()) {
            var offsets = slice(grid, Field.CellOffset, false);
            sort.prefix(encoder, counts, offsets, allocated.cells());
            sort.scatter(encoder, keys, read(grid, Field.OutputParticleIdIndex), offsets, slice(grid, Field.ParticleList, false),
                    read(grid, Field.OutputAcquireTag), write(grid, Field.AcquireTagList),
                    slots, allocated.slotsPerParticle(), allocated.persistentIds());
        }
    }

    GpuBufferSlice slice(Grid grid, Field field, boolean outputStage) {
        if (field.writable()) return outputStage ? write(grid, field) : emptyWrite(field);
        return outputStage ? emptyRead() : read(grid, field);
    }

    private GpuBufferSlice read(Grid grid, Field field) {
        var buffer = grid.buffers.get(field);
        return buffer != null ? buffer.slice() : emptyRead();
    }

    private GpuBufferSlice write(Grid grid, Field field) {
        var buffer = grid.buffers.get(field);
        return buffer != null ? buffer.slice() : emptyWrite(field);
    }

    private GpuBufferSlice emptyRead() { return programs.dummyBuffer().slice(0, Integer.BYTES); }
    private GpuBufferSlice emptyWrite(Field field) {
        var buffer = emptyWrites.computeIfAbsent(field, key -> NiagaraGpuNeighborQuerySort.allocate("Niagara.NeighborQuery.Empty." + key, 1));
        var slice = buffer.slice();
        programs.runtime().clearBuffer(slice, 0);
        return slice;
    }

    @Override public void close() {
        for (var grid : grids.values()) grid.buffers.values().forEach(GpuBuffer::close);
        grids.clear();
        emptyWrites.values().forEach(GpuBuffer::close);
        emptyWrites.clear();
        sort.close();
    }
}
