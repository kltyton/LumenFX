package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraVelocityGrid.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Render-thread ownership and transfer operations for HairStrands Niagara grids. */
public final class NiagaraGpuVelocityGrids implements AutoCloseable {
    private static final int PRESSURE_ATTRIBUTE = 6;
    private static final int DISTANCE_ATTRIBUTE = 14;
    private static final int MAX_DISTANCE = 100_000_000;

    record Grid(long allocation, long attributeBytes, GpuBuffer current, GpuBuffer destination) {
        void retire() {
            RenderSystem.queueFencedTask(() -> {
                try { current.close(); }
                finally { destination.close(); }
            });
        }
    }

    private final ComputeRuntime runtime;
    private final Map<String, Grid> grids = new HashMap<>();
    private @Nullable Grid empty;

    public NiagaraGpuVelocityGrids(ComputeRuntime runtime) { this.runtime = runtime; }

    @Nullable Grid prepare(String id, Snapshot snapshot) {
        var previous = grids.get(id);
        if (!snapshot.allocated()) {
            if (previous != null) {
                grids.remove(id);
                previous.retire();
            }
            return null;
        }
        if (previous != null && previous.allocation() == snapshot.allocation()) return previous;
        long attributeBytes = Math.multiplyExact(snapshot.size().words(1), Integer.BYTES);
        long bytes = Math.multiplyExact(attributeBytes, snapshot.attributes());
        if (bytes > runtime.maxStorageBufferBytes() || bytes / Integer.BYTES > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Niagara Velocity/Pressure Grid exceeds signed indexing or storage limits: " + id);
        var next = allocate(id, bytes, attributeBytes, snapshot.allocation());
        grids.put(id, next);
        if (previous != null) previous.retire();
        return next;
    }

    Grid empty() {
        if (empty == null) empty = allocate("Niagara.VelocityGrid.Empty", Integer.BYTES, Integer.BYTES, 0);
        return empty;
    }

    private Grid allocate(String id, long bytes, long attributeBytes, long allocation) {
        var device = RenderSystem.getDevice();
        int usage = ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
        var current = device.createBuffer(() -> id + ".GridCurrentBuffer", usage, bytes);
        GpuBuffer destination;
        try { destination = device.createBuffer(() -> id + ".GridDestinationBuffer", usage, bytes); }
        catch (RuntimeException | Error failure) { current.close(); throw failure; }
        var grid = new Grid(allocation, attributeBytes, current, destination);
        try { reset(grid); }
        catch (RuntimeException | Error failure) { grid.retire(); throw failure; }
        return grid;
    }

    void beginStage(CommandEncoder encoder, Grid grid, boolean pressure) {
        runtime.clearBuffer(grid.destination().slice(), 0);
        if (pressure) {
            long span = grid.attributeBytes();
            long pressureOffset = span * PRESSURE_ATTRIBUTE;
            // The source clear kernel lays out each attribute as one contiguous node volume.
            encoder.copyToBuffer(grid.current().slice(pressureOffset, span), grid.destination().slice(pressureOffset, span));
            runtime.clearBuffer(grid.destination().slice(span * DISTANCE_ATTRIBUTE, span), MAX_DISTANCE);
        }
    }

    void reset(Grid grid) {
        runtime.clearBuffer(grid.current().slice(), 0);
        runtime.clearBuffer(grid.destination().slice(), 0);
    }

    @Override public void close() {
        grids.values().forEach(Grid::retire);
        grids.clear();
        if (empty != null) {
            empty.retire();
            empty = null;
        }
    }
}
