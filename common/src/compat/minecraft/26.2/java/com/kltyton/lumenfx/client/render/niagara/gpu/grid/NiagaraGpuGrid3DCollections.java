package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid.Extent;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureVolume;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid3DCollection.Snapshot;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/** Render-thread current and destination volumes of each physical Grid3D Collection. */
public final class NiagaraGpuGrid3DCollections implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Settings SAMPLER = new Settings(Address.CLAMP, Address.CLAMP, FilterMode.LINEAR, FilterMode.POINT, 1);
    private static final ComputeVolume.Sampling SAMPLING = new ComputeVolume.Sampling(SAMPLER, Address.CLAMP);
    private static final float[] ZERO = new float[4];

    final class Grid implements AutoCloseable {
        final String identity;
        final Snapshot state;
        final int formatIndex;
        final GpuBuffer offsets;
        private final ArrayList<ComputeVolume> buffers = new ArrayList<>(2);
        @Nullable ComputeVolume current;
        @Nullable ComputeVolume destination;
        private boolean reportedTargetMismatch;

        private Grid(String identity, Snapshot state) {
            this.identity = identity;
            this.state = state;
            formatIndex = state.layout().attributes() == 0 ? 7
                    : NiagaraGridStorageFormats.select(Math.max(1, state.packedComponents()), state.format(),
                            value -> supported.computeIfAbsent(value, ComputeVolume::supportsGridFormat));
            offsets = createOffsets(identity, state);
        }

        GpuFormat format() { return RenderTargetTextureDefinition.gpuFormat(formatIndex); }

        void beginOutput(boolean usesPrevious, Extent dispatch) {
            destination = null;
            if (state.layout().attributes() == 0) return;
            var layout = state.layout();
            for (var volume : buffers) {
                if (usesPrevious && volume == current) continue;
                destination = volume;
                break;
            }
            if (destination == null) {
                destination = ComputeVolume.createTarget(identity + ".Grid", layout.width(), layout.height(), layout.depth(),
                        format(), true, false, ZERO, SAMPLING);
                buffers.add(destination);
            } else if (destination != current && state.clearOutput()
                    && (dispatch.x() != layout.x() || dispatch.y() != layout.y() || dispatch.z() != layout.z())) {
                destination.clearColor(ZERO);
            }
        }

        void finishOutput() {
            current = destination;
            destination = null;
        }

        void reset() { for (var volume : buffers) volume.clearColor(ZERO); }

        void copyOutput(@Nullable RenderTargetTextureVolume target) {
            if (target == null || target.closed() || current == null) return;
            var layout = state.layout();
            int scalar = NiagaraGridStorageFormats.select(1, state.format(),
                    value -> supported.computeIfAbsent(value, ComputeVolume::supportsGridFormat));
            String source = RenderTargetTextureDefinition.sourceFormat(scalar);
            var surface = new RenderTargetTextureDefinition(layout.width(), layout.height(), RenderTargetTextureDefinition.gpuFormat(scalar), source,
                    false, false, ZERO, SAMPLER, SAMPLER, scalar, true, true, "TF_Bilinear");
            target.configure(surface, layout.depth());
            if (scalar != formatIndex) {
                if (!reportedTargetMismatch)
                    LOGGER.warn("Niagara Grid3D Collection {} cannot copy packed {} storage into scalar target {}", identity, format(), surface.format());
                reportedTargetMismatch = true;
                return;
            }
            current.copyTo(Objects.requireNonNull(target.resolve().volume()));
        }

        @Override public void close() {
            try { buffers.forEach(ComputeVolume::close); }
            finally { offsets.close(); }
        }
    }

    private final NiagaraGpuPrograms programs;
    private final Map<String, Grid> grids = new HashMap<>();
    private final Map<GpuFormat, Boolean> supported = new EnumMap<>(GpuFormat.class);
    private final Map<GpuFormat, ComputeVolume> emptyWrites = new EnumMap<>(GpuFormat.class);
    private @Nullable ComputeVolume emptyRead;

    public NiagaraGpuGrid3DCollections(NiagaraGpuPrograms programs) { this.programs = programs; }

    Grid prepare(String identity, Snapshot state) {
        var previous = grids.get(identity);
        if (previous != null && previous.state.equals(state)) return previous;
        var replacement = new Grid(identity, state);
        grids.put(identity, replacement);
        if (previous != null) previous.close();
        return replacement;
    }

    private GpuBuffer createOffsets(String label, Snapshot state) {
        var layout = state.layout();
        long byteCount = layout.offsetBytes();
        if (byteCount > Math.min(Integer.MAX_VALUE, programs.runtime().maxStorageBufferBytes()))
            throw new IllegalArgumentException("Niagara Grid3D Collection attribute table exceeds storage limits: " + label);
        var bytes = MemoryUtil.memAlloc((int)byteCount).order(ByteOrder.LITTLE_ENDIAN);
        try {
            layout.writeOffsets(bytes.asFloatBuffer());
            return RenderSystem.getDevice().createBuffer(() -> label + ".PerAttributeData",
                    ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    GpuBufferSlice offsets(Grid grid) { return grid.offsets.slice(); }

    ComputeVolume emptyRead() {
        if (emptyRead == null) emptyRead = ComputeVolume.createTarget("Niagara.Grid3D.Black", 1, 1, 1,
                GpuFormat.R32_FLOAT, false, false, ZERO, SAMPLING);
        return emptyRead;
    }

    ComputeVolume emptyWrite(GpuFormat format) {
        return emptyWrites.computeIfAbsent(format, value -> ComputeVolume.createTarget("Niagara.Grid3D.EmptyUAV",
                1, 1, 1, value, true, false, ZERO, SAMPLING));
    }

    @Override public void close() {
        grids.values().forEach(Grid::close);
        grids.clear();
        emptyWrites.values().forEach(ComputeVolume::close);
        emptyWrites.clear();
        if (emptyRead != null) { emptyRead.close(); emptyRead = null; }
        supported.clear();
    }
}
