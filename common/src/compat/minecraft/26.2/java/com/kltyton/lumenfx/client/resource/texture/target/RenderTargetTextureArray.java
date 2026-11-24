package com.kltyton.lumenfx.client.resource.texture.target;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** A stable render target object that can replace its array image while queued consumers keep old allocations. */
public final class RenderTargetTextureArray implements DimensionalRenderTarget {
    private static final class Allocation {
        final ComputeTexture image;
        final GpuSampler sampler;
        int references = 1;

        Allocation(ComputeTexture image, GpuSampler sampler) { this.image = image; this.sampler = sampler; }

        Retention retain() {
            var retention = new Retention(this::release);
            references = Math.incrementExact(references);
            return retention;
        }

        void release() {
            if (--references != 0) return;
            try { sampler.close(); }
            finally { image.close(); }
        }
    }

    private final Identifier id;
    private final boolean clearAll;
    private final float targetGamma;
    private final boolean forceLinearGamma;
    private RenderTargetTextureDefinition settings;
    private int layers;
    private @Nullable Allocation allocation;
    private boolean closed;

    public RenderTargetTextureArray(Identifier id, ComputeRuntime runtime, RenderTargetTextureDefinition settings,
                                    int layers, boolean clearAll) {
        this(id, runtime, settings, layers, clearAll, 0, true);
    }

    public RenderTargetTextureArray(Identifier id, ComputeRuntime runtime, RenderTargetTextureDefinition settings,
                                    int layers, boolean clearAll, float targetGamma, boolean forceLinearGamma) {
        if (!Float.isFinite(targetGamma)) throw new IllegalArgumentException("Invalid array target gamma");
        this.id = id;
        this.clearAll = clearAll;
        this.targetGamma = targetGamma;
        this.forceLinearGamma = forceLinearGamma;
        this.settings = settings;
        configure(runtime, settings, layers);
    }

    public RenderTargetTextureDefinition settings() { return settings; }
    public int layers() { return layers; }
    @Override public Dimension dimension() { return Dimension.ARRAY; }
    @Override public int extentZ() { return layers; }
    public boolean closed() { return closed; }
    public boolean srgbFor(String pixelFormat) {
        return targetGamma > .001f ? Math.abs(targetGamma - 1f) >= .0001f
                : !forceLinearGamma && !pixelFormat.equals("PF_FloatRGB") && !pixelFormat.equals("PF_FloatRGBA");
    }

    @Override public void configure(ComputeRuntime runtime, RenderTargetTextureDefinition next, int layers) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Render target array was released");
        if (layers < 1 || next.autoMips()) throw new IllegalArgumentException("Texture render target arrays have a single mip");
        var previous = allocation;
        if (previous != null && this.layers == layers && settings.width() == next.width() && settings.height() == next.height()
                && settings.format() == next.format() && settings.sourceFormat().equals(next.sourceFormat()) && settings.srgb() == next.srgb()
                && settings.filter().equals(next.filter())
                && settings.storageWritable() == next.storageWritable() && settings.sampler().equals(next.sampler())) return;
        boolean encoded = next.srgb() && next.format() == GpuFormat.RGBA8_UNORM;
        var image = runtime.createColorTargetArray(id.toString(), next.format(), next.width(), next.height(), layers,
                next.storageWritable(), encoded);
        GpuSampler sampler;
        try {
            runtime.clearColorArray(image, clearAll ? layers : 1, next.clearColor(), encoded);
            sampler = EffectSamplerFactory.create(next.sampler(), 1);
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
        allocation = new Allocation(image, sampler);
        settings = next;
        this.layers = layers;
        if (previous != null) previous.release();
    }

    @Override public Snapshot resolve() {
        RenderSystem.assertOnRenderThread();
        var current = allocation;
        return current == null ? Snapshot.UNBOUND : TextureParameter.snapshotArray(current.image, current.sampler);
    }

    @Override public @Nullable Retention retain(Snapshot snapshot) {
        RenderSystem.assertOnRenderThread();
        var current = allocation;
        return current != null && current.image == snapshot.arrayTexture() && current.sampler == snapshot.sampler()
                ? current.retain() : null;
    }

    @Override public boolean retainsAcrossFrames() { return true; }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        var previous = allocation;
        allocation = null;
        if (previous != null) previous.release();
    }
}
