package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraRasterizationGrid.Snapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Signed rasterization volumes and packed tile metadata owned by the render thread. */
public final class NiagaraGpuRasterizationGrids implements AutoCloseable {
    private static final int FLOAT4_BYTES = 4 * Float.BYTES;
    record Grid(Snapshot state, @Nullable ComputeVolume volume, @Nullable GpuBuffer offsets) implements AutoCloseable {
        @Override public void close() {
            try { if (offsets != null) offsets.close(); }
            finally { if (volume != null) volume.close(); }
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Grid> grids = new HashMap<>();
    private @Nullable ComputeVolume emptyRead;
    private @Nullable ComputeVolume emptyWrite;
    private @Nullable GpuBuffer emptyOffsets;

    public NiagaraGpuRasterizationGrids(NiagaraGpuPrograms programs) { this.programs = programs; }

    Grid prepare(String identity, Snapshot state, int maximum) {
        var previous = grids.get(identity);
        boolean allocate = state.hasStorage(maximum);
        if (previous != null && previous.state().equals(state) && (previous.volume() != null || !allocate)) return previous;
        var priorVolume = previous == null ? null : previous.volume();
        var layout = state.layout();
        // A resize over the live CVar limit leaves the existing pooled image available to the source proxy.
        var volume = allocate ? ComputeVolume.createSignedGrid(identity + ".IntGrid", layout.width(), layout.height(), layout.depth(), true)
                : layout.fits() ? priorVolume : null;
        Grid next;
        try {
            next = new Grid(state, volume, volume == null ? null : createOffsets(identity, state));
        } catch (RuntimeException | Error failure) {
            if (volume != null && volume != priorVolume) volume.close();
            throw failure;
        }
        grids.put(identity, next);
        if (previous != null) {
            if (volume == priorVolume) {
                if (previous.offsets() != null) previous.offsets().close();
            } else previous.close();
        }
        return next;
    }

    private GpuBuffer createOffsets(String identity, Snapshot state) {
        var layout = state.layout();
        long byteCount = layout.offsetBytes();
        if (byteCount > Math.min(Integer.MAX_VALUE, programs.runtime().maxStorageBufferBytes()))
            throw new IllegalArgumentException("Niagara Rasterization Grid3D attribute table exceeds storage limits: " + identity);
        var bytes = MemoryUtil.memAlloc((int)byteCount).order(ByteOrder.LITTLE_ENDIAN);
        try {
            layout.writeOffsets(bytes.asFloatBuffer());
            return RenderSystem.getDevice().createBuffer(() -> identity + ".PerAttributeData",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    GpuBufferSlice offsets(Grid grid) {
        if (grid.offsets() != null) return grid.offsets().slice();
        if (emptyOffsets == null) {
            var empty = RenderSystem.getDevice().createBuffer(() -> "Niagara.RasterizationGrid.EmptyAttribute",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), FLOAT4_BYTES);
            try { programs.runtime().clearBuffer(empty.slice(), 0); }
            catch (RuntimeException | Error failure) { empty.close(); throw failure; }
            emptyOffsets = empty;
        }
        return emptyOffsets.slice();
    }

    ComputeVolume emptyRead() {
        if (emptyRead == null) emptyRead = ComputeVolume.createSignedGrid("Niagara.RasterizationGrid.Black", 1, 1, 1, false);
        return emptyRead;
    }

    ComputeVolume emptyWrite() {
        if (emptyWrite == null) emptyWrite = ComputeVolume.createSignedGrid("Niagara.RasterizationGrid.EmptyUAV", 1, 1, 1, true);
        return emptyWrite;
    }

    @Override public void close() {
        grids.values().forEach(Grid::close);
        grids.clear();
        if (emptyRead != null) { emptyRead.close(); emptyRead = null; }
        if (emptyWrite != null) { emptyWrite.close(); emptyWrite = null; }
        if (emptyOffsets != null) { emptyOffsets.close(); emptyOffsets = null; }
    }
}
