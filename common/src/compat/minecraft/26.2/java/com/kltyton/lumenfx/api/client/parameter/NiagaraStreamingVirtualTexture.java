package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Layer;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Uint4;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A streamed UTexture-style page publication for the Virtual Texture Sample interface. */
public interface NiagaraStreamingVirtualTexture {
    /** Returns null while the texture resource is initializing, compiling or otherwise unavailable. */
    @Nullable Lease acquire();

    /** Requests a normalized UV region through the producer, independently of Niagara's explicit LOD sampling. */
    void request(Feedback region);

    record Snapshot(int width, int height, int mips, Layer layer, @Nullable GpuTextureView secondTable,
                    Uint4 table0, Uint4 table1, GpuSampler sampler, List<Layer> layers, int addressU, int addressV) {
        public Snapshot(int width, int height, int mips, Layer layer, @Nullable GpuTextureView secondTable,
                        Uint4 table0, Uint4 table1, GpuSampler sampler) {
            this(width, height, mips, layer, secondTable, table0, table1, sampler, List.of(layer), 0, 0);
        }
        public Snapshot {
            if (width <= 0 || height <= 0 || mips <= 0) throw new IllegalArgumentException("Invalid streamed Niagara VT size");
            Objects.requireNonNull(layer);
            Objects.requireNonNull(table0);
            Objects.requireNonNull(table1);
            Objects.requireNonNull(sampler);
            layers = List.copyOf(layers);
            if (layers.isEmpty() || layers.size() > 8 || !layers.getFirst().equals(layer)
                    || addressU < 0 || addressU > 2 || addressV < 0 || addressV > 2)
                throw new IllegalArgumentException("Invalid streamed Niagara VT layer or address layout");
            if (secondTable != null && (secondTable.isClosed() || secondTable.texture().isClosed()
                    || secondTable.texture().getFormat() != GpuFormat.RGBA32_UINT || secondTable.texture().getDepthOrLayers() != 1
                    || (secondTable.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0))
                throw new IllegalArgumentException("Niagara VT requires a live second uint4 page table");
        }
    }

    final class Lease implements AutoCloseable {
        private final Snapshot snapshot;
        private @Nullable Runnable release;

        public Lease(Snapshot snapshot, Runnable release) {
            this.snapshot = Objects.requireNonNull(snapshot);
            this.release = Objects.requireNonNull(release);
        }
        public Snapshot snapshot() { return snapshot; }
        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            var action = release;
            release = null;
            if (action != null) action.run();
        }
    }
}
