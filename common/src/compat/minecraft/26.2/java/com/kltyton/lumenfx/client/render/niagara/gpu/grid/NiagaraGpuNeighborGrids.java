package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborGrid.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Render-thread grid buffers, shared by every alias of the same physical interface. */
public final class NiagaraGpuNeighborGrids implements AutoCloseable {
    record Buffers(GpuBuffer neighbors, GpuBuffer counts) implements AutoCloseable {
        @Override public void close() {
            try { neighbors.close(); }
            finally { counts.close(); }
        }
    }
    record Grid(Snapshot snapshot, @Nullable Buffers buffers) { }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Grid> grids = new HashMap<>();
    private final GpuBuffer[] emptyWrites = new GpuBuffer[2];

    public NiagaraGpuNeighborGrids(NiagaraGpuPrograms programs) { this.programs = programs; }

    Grid prepare(String identity, Snapshot snapshot, long maximumCells) {
        var previous = grids.get(identity);
        if (previous != null && previous.snapshot().equals(snapshot)) return previous;
        var previousBuffers = previous == null ? null : previous.buffers();
        var buffers = snapshot.cells() <= maximumCells ? allocate(identity, snapshot) : previousBuffers;
        var grid = new Grid(snapshot, buffers);
        grids.put(identity, grid);
        if (previousBuffers != null && buffers != previousBuffers) previousBuffers.close();
        return grid;
    }

    private Buffers allocate(String identity, Snapshot snapshot) {
        var neighbors = create(identity + ".Neighbors", snapshot.elements());
        GpuBuffer counts = null;
        try {
            counts = create(identity + ".Counts", snapshot.cells());
            var buffers = new Buffers(neighbors, counts);
            clear(buffers);
            return buffers;
        } catch (RuntimeException | Error failure) {
            if (counts != null) counts.close();
            neighbors.close();
            throw failure;
        }
    }

    private static GpuBuffer create(String label, int elements) {
        return RenderSystem.getDevice().createBuffer(() -> label, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST),
                (long)elements * Integer.BYTES);
    }

    void clear(Grid grid) {
        if (grid.buffers() != null) clear(grid.buffers());
    }

    private void clear(Buffers buffers) {
        programs.runtime().clearBuffer(buffers.neighbors().slice(), -1);
        programs.runtime().clearBuffer(buffers.counts().slice(), 0);
    }

    GpuBufferSlice emptyRead() { return programs.dummyBuffer().slice(0, Integer.BYTES); }
    GpuBufferSlice emptyWrite(boolean counts) {
        int index = counts ? 1 : 0;
        if (emptyWrites[index] == null) emptyWrites[index] = create("Niagara.NeighborGrid.EmptyUAV", 1);
        var slice = emptyWrites[index].slice();
        programs.runtime().clearBuffer(slice, 0);
        return slice;
    }

    @Override public void close() {
        for (var grid : grids.values()) if (grid.buffers() != null) grid.buffers().close();
        grids.clear();
        for (int index = 0; index < emptyWrites.length; index++) {
            if (emptyWrites[index] != null) emptyWrites[index].close();
            emptyWrites[index] = null;
        }
    }
}
