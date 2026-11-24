package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** A runtime virtual texture producer shared by Landscape and Niagara RVT sampling. */
public interface NiagaraRuntimeVirtualTexture {
    enum Material {
        BaseColor, Mask4, BaseColor_Normal_Roughness, BaseColor_Normal_Specular, BaseColor_Normal_Specular_YCoCg,
        BaseColor_Normal_Specular_Mask_YCoCg, WorldHeight, Displacement
    }
    record Uint4(int x, int y, int z, int w) { }

    record Layer(GpuTextureView physical, GpuTextureView pageTable, Uint4 uniforms, boolean srgb, boolean yCoCg) {
        public Layer {
            requireView(physical);
            requireView(pageTable);
            Objects.requireNonNull(uniforms);
            if (!SampledTextureFormats.floatingColor(physical.texture()) || pageTable.texture().getFormat() != GpuFormat.RGBA32_UINT)
                throw new IllegalArgumentException("Niagara virtual textures require floating color and widened uint4 page-table views");
        }
        private static void requireView(GpuTextureView view) {
            if (view.isClosed() || view.texture().isClosed() || view.texture().getDepthOrLayers() != 1
                    || (view.texture().usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0)
                throw new IllegalArgumentException("Niagara virtual texture requires a live sampled 2D view");
        }
    }

    /** Fine adaptive pages used by material draws. Niagara keeps the persistent coarse table in each Layer. */
    record AdaptivePages(GpuTextureView indirection, GpuTextureView pages) {
        public AdaptivePages {
            for (var view : new GpuTextureView[] { indirection, pages }) {
                Layer.requireView(view);
                if (view.texture().getFormat() != GpuFormat.R32_UINT)
                    throw new IllegalArgumentException("Adaptive RVT page tables require R32_UINT views");
            }
        }
    }

    record Snapshot(Material material, Vec3 origin, Vec3 u, Vec3 v, float heightScale, float heightBias,
                    Uint4 table0, Uint4 table1, Map<Integer, Layer> layers, GpuSampler sampler, @Nullable AdaptivePages adaptive) {
        public Snapshot(Material material, Vec3 origin, Vec3 u, Vec3 v, float heightScale, float heightBias,
                        Uint4 table0, Uint4 table1, Map<Integer, Layer> layers, GpuSampler sampler) {
            this(material, origin, u, v, heightScale, heightBias, table0, table1, layers, sampler, null);
        }
        public Snapshot {
            Objects.requireNonNull(material);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(u);
            Objects.requireNonNull(v);
            Objects.requireNonNull(table0);
            Objects.requireNonNull(table1);
            Objects.requireNonNull(sampler);
            layers = Map.copyOf(layers);
            for (int layer : layers.keySet()) Objects.checkIndex(layer, 3);
        }

        public Snapshot withAdaptive(AdaptivePages pages) {
            return new Snapshot(material, origin, u, v, heightScale, heightBias, table0, table1, layers, sampler, pages);
        }
    }

    record Feedback(double minU, double minV, double maxU, double maxV, int mip) { }

    /** Acquire a lease for this exact publication, including both the page tables and the physical images. */
    @Nullable Lease acquire();

    /** Enqueue a request on the producer for the next residency update. */
    void request(Feedback region);

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
