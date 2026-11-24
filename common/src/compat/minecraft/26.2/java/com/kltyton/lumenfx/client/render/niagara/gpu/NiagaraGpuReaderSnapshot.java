package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/** Snapshot of a particle dataset for a reader in an in-place GPU simulation stage. */
final class NiagaraGpuReaderSnapshot implements AutoCloseable {
    private final String name;
    private @Nullable NiagaraGpuDataBuffer data;
    private @Nullable GpuBuffer idTable;
    private int countOffset = -1;

    NiagaraGpuReaderSnapshot(String name) { this.name = name; }

    void capture(CommandEncoder encoder, NiagaraGpuParticleBuffers particles) {
        RenderSystem.assertOnRenderThread();
        NiagaraGpuDataBuffer source = particles.readerCurrentData();
        if (data == null || data.stride() != source.stride()) {
            NiagaraGpuDataBuffer replacement = new NiagaraGpuDataBuffer(name + ".ParticleReadSnapshot",
                    source.stride() - 1, particles.readerAttributes());
            if (data != null) data.close();
            data = replacement;
        }
        copy(encoder, source.floats(), data.floats());
        copy(encoder, source.integers(), data.integers());
        copy(encoder, source.halves(), data.halves());
        GpuBuffer sourceIds = particles.readerIdTable();
        if (sourceIds != null) {
            if (idTable == null || idTable.size() != sourceIds.size()) {
                GpuBuffer replacement = RenderSystem.getDevice().createBuffer(() -> name + ".ParticleReadIDs",
                        ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC), sourceIds.size());
                if (idTable != null) idTable.close();
                idTable = replacement;
            }
            encoder.copyToBuffer(sourceIds.slice(), idTable.slice());
        } else if (idTable != null) {
            idTable.close();
            idTable = null;
        }
        int sourceCountOffset = particles.readerCurrentCountOffset();
        countOffset = sourceCountOffset < 0 ? -1 : particles.readerSnapshotCountOffset();
        if (sourceCountOffset >= 0) {
            if (countOffset < 0) throw new IllegalStateException("Missing Niagara GPU particle-reader snapshot counter");
            var counts = particles.readerCountBuffer();
            if (sourceCountOffset != countOffset)
                encoder.copyToBuffer(counts.slice((long)sourceCountOffset * Integer.BYTES, Integer.BYTES),
                        counts.slice((long)countOffset * Integer.BYTES, Integer.BYTES));
        }
    }

    private static void copy(CommandEncoder encoder, @Nullable GpuBuffer source, @Nullable GpuBuffer destination) {
        if (source == null && destination == null) return;
        if (source == null || destination == null || source.size() != destination.size())
            throw new IllegalStateException("Niagara GPU reader snapshot layout changed");
        encoder.copyToBuffer(source.slice(), destination.slice());
    }

    NiagaraGpuDataBuffer data() {
        if (data == null) throw new IllegalStateException("Niagara GPU reader snapshot is unavailable");
        return data;
    }
    @Nullable GpuBuffer idTable() { return idTable; }
    int countOffset() { return countOffset; }

    @Override public void close() {
        if (data != null) data.close();
        if (idTable != null) idTable.close();
        data = null; idTable = null; countOffset = -1;
    }
}
