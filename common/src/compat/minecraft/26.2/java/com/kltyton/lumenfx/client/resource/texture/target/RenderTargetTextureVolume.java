package com.kltyton.lumenfx.client.resource.texture.target;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** The source volume target keeps object identity across resizes while consumers retain each physical image. */
public final class RenderTargetTextureVolume implements DimensionalRenderTarget {
    private final Identifier id;
    private final float targetGamma;
    private final boolean forceLinearGamma;
    private RenderTargetTextureDefinition settings;
    private int depth;
    private @Nullable ComputeVolume image;
    private boolean closed;

    public RenderTargetTextureVolume(Identifier id, RenderTargetTextureDefinition settings, int depth,
                                     float targetGamma, boolean forceLinearGamma) {
        if (!Float.isFinite(targetGamma)) throw new IllegalArgumentException("Invalid volume target gamma");
        this.id = id;
        this.settings = settings;
        this.targetGamma = targetGamma;
        this.forceLinearGamma = forceLinearGamma;
        configure(settings, depth);
    }

    public RenderTargetTextureDefinition settings() { return settings; }
    public int depth() { return depth; }
    @Override public Dimension dimension() { return Dimension.VOLUME; }
    @Override public int extentZ() { return depth; }
    public boolean closed() { return closed; }
    public boolean srgbFor(String pixelFormat) {
        return targetGamma > .001f ? Math.abs(targetGamma - 1f) >= .0001f
                : !forceLinearGamma && !pixelFormat.equals("PF_R32_FLOAT") && !pixelFormat.equals("PF_FloatRGBA");
    }

    public void configure(RenderTargetTextureDefinition next, int depth) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Volume target was released");
        if (depth < 1 || next.autoMips()) throw new IllegalArgumentException("Volume targets have a single mip");
        var previous = image;
        if (previous != null && this.depth == depth && settings.width() == next.width() && settings.height() == next.height()
                && settings.format() == next.format() && settings.sourceFormat().equals(next.sourceFormat())
                && settings.srgb() == next.srgb() && settings.filter().equals(next.filter())
                && settings.storageWritable() == next.storageWritable() && settings.sampler().equals(next.sampler())) return;
        var sampling = new ComputeVolume.Sampling(next.sampler(), TextureMetadata.Address.WRAP);
        var candidate = ComputeVolume.createTarget(id.toString(), next.width(), next.height(), depth, next.format(),
                next.storageWritable(), next.srgb() && next.format() == GpuFormat.RGBA8_UNORM, next.clearColor(), sampling);
        image = candidate;
        settings = next;
        this.depth = depth;
        if (previous != null) previous.close();
    }
    @Override public void configure(ComputeRuntime runtime, RenderTargetTextureDefinition next, int depth) {
        configure(next, depth);
    }

    @Override public Snapshot resolve() {
        RenderSystem.assertOnRenderThread();
        var current = image;
        return current == null ? Snapshot.UNBOUND : TextureParameter.snapshotVolume(current);
    }
    @Override public @Nullable Retention retain(Snapshot snapshot) {
        var current = image;
        if (current == null || snapshot.volume() != current) return null;
        var lease = current.retain();
        return new Retention(lease::close);
    }
    @Override public boolean retainsAcrossFrames() { return true; }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        var previous = image;
        image = null;
        if (previous != null) previous.close();
    }
}
