package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuDispatchGrid.Extent;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureArray;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraGrid2DCollection.Snapshot;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuGrid2DCollections implements AutoCloseable {
    private static final float[] ZERO = new float[4];
    private static final float[] TARGET_CLEAR = {0.5f, 0, 0, 0};
    private static final Settings SAMPLER = new Settings(Address.CLAMP, Address.CLAMP, FilterMode.LINEAR, FilterMode.POINT, 1);

    final class Grid implements AutoCloseable {
        final String identity;
        final Snapshot state;
        final int formatIndex;
        final ArrayList<ComputeTexture> buffers = new ArrayList<>(2);
        @Nullable ComputeTexture current, destination;

        Grid(String identity, Snapshot state) {
            this.identity = identity;
            this.state = state;
            formatIndex = state.layers() == 0 ? 7 : NiagaraGridStorageFormats.select(1, state.format(),
                    format -> supported.computeIfAbsent(format, runtime::supportsGridArrayFormat));
        }

        GpuFormat format() { return RenderTargetTextureDefinition.gpuFormat(formatIndex); }

        void beginOutput(boolean input, Extent elements) {
            destination = null;
            if (state.layers() == 0) return;
            for (var buffer : buffers) if (!input || buffer != current) {
                destination = buffer;
                break;
            }
            if (destination == null) {
                destination = create(identity + ".Grid", format(), state.x(), state.y(), state.layers(), true);
                buffers.add(destination);
            } else if (state.clearOutput() && (state.x() != elements.x() || state.y() != elements.y())) {
                runtime.clearColorArray(destination, state.layers(), ZERO, false);
            }
        }

        void finishOutput() { current = destination; destination = null; }
        void reset() { for (var buffer : buffers) runtime.clearColorArray(buffer, state.layers(), ZERO, false); }

        void publish(@Nullable TextureParameter target) {
            var source = current;
            if (target == null || source == null) return;
            if (target instanceof RenderTargetTextureArray array && !array.closed()) {
                array.configure(runtime, targetDefinition(state.x(), state.y(), TARGET_CLEAR, array.settings().storageWritable()), state.layers());
                runtime.copyArrayRegion(source, 0, 0, Objects.requireNonNull(array.resolve().arrayTexture()), 0, 0, 0,
                        state.x(), state.y(), state.layers());
            } else if (target instanceof RenderTargetTexture texture && !texture.isClosed()) {
                int maximum = runtime.maxTexture2D();
                int tilesX = Math.min(maximum / state.x(), state.layers());
                if (tilesX <= 0) return;
                int tilesY = Math.ceilDiv(state.layers(), tilesX);
                if (tilesY > maximum / state.y()) return;
                texture.configureStorage(runtime, targetDefinition(tilesX * state.x(), tilesY * state.y(), TARGET_CLEAR, true));
                var image = Objects.requireNonNull(texture.storageImage());
                for (int layer = 0; layer < state.layers(); layer++) runtime.copyArrayRegion(source, layer, 0, image, 0,
                        (layer % tilesX) * state.x(), (layer / tilesX) * state.y(), state.x(), state.y(), 1);
                texture.storageWritesCompleted();
            }
        }

        private RenderTargetTextureDefinition targetDefinition(int width, int height, float[] clear, boolean writable) {
            return new RenderTargetTextureDefinition(width, height, format(), RenderTargetTextureDefinition.sourceFormat(formatIndex),
                    false, false, clear, SAMPLER, SAMPLER, formatIndex, true, writable, "TF_Bilinear");
        }

        @Override public void close() { buffers.forEach(ComputeTexture::close); }
    }

    private final ComputeRuntime runtime;
    private final Map<String, Grid> grids = new HashMap<>();
    private final Map<GpuFormat, Boolean> supported = new EnumMap<>(GpuFormat.class);
    private final Map<GpuFormat, ComputeTexture> emptyWrites = new EnumMap<>(GpuFormat.class);
    private @Nullable ComputeTexture emptyRead;
    private @Nullable GpuSampler sampler;

    public NiagaraGpuGrid2DCollections(ComputeRuntime runtime) { this.runtime = runtime; }

    Grid prepare(String identity, Snapshot state) {
        var previous = grids.get(identity);
        if (previous != null && previous.state.equals(state)) return previous;
        var replacement = new Grid(identity, state);
        grids.put(identity, replacement);
        if (previous != null) previous.close();
        return replacement;
    }

    GpuSampler sampler() {
        if (sampler == null) sampler = EffectSamplerFactory.create(SAMPLER, 1);
        return sampler;
    }

    ComputeTexture emptyRead() {
        if (emptyRead == null) emptyRead = create("Niagara.Grid2D.Black", GpuFormat.R32_FLOAT, 1, 1, 1, false);
        return emptyRead;
    }

    ComputeTexture emptyWrite(GpuFormat format) {
        return emptyWrites.computeIfAbsent(format, value -> create("Niagara.Grid2D.EmptyUAV", value, 1, 1, 1, true));
    }

    private ComputeTexture create(String name, GpuFormat format, int width, int height, int layers, boolean writable) {
        var texture = runtime.createColorTargetArray(name, format, width, height, layers, writable, false);
        try {
            runtime.clearColorArray(texture, layers, ZERO, false);
            return texture;
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    @Override public void close() {
        grids.values().forEach(Grid::close);
        grids.clear();
        emptyWrites.values().forEach(ComputeTexture::close);
        emptyWrites.clear();
        if (emptyRead != null) { emptyRead.close(); emptyRead = null; }
        if (sampler != null) { sampler.close(); sampler = null; }
        supported.clear();
    }
}
