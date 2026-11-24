package com.kltyton.lumenfx.client.resource.texture.target;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.texture.cube.CubeColorTarget;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Owns both views of one cube target and retains the cube and sampler across deferred work. */
public final class RenderTargetTextureCube implements DimensionalRenderTarget {
    private static final class Allocation {
        final CubeColorTarget image;
        final GpuSampler sampler;
        private int references = 1;
        Allocation(CubeColorTarget image, GpuSampler sampler) { this.image = image; this.sampler = sampler; }

        Retention retain() {
            var lease = new Retention(this::release);
            references = Math.incrementExact(references);
            return lease;
        }

        void release() {
            if (--references != 0) return;
            try { sampler.close(); }
            finally { image.close(); }
        }
    }

    private final Identifier id;
    private final float targetGamma;
    private final boolean forceLinearGamma;
    private RenderTargetTextureDefinition settings;
    private @Nullable Allocation allocation;
    private boolean closed;

    public RenderTargetTextureCube(Identifier id, RenderTargetTextureDefinition settings, float targetGamma, boolean forceLinearGamma) {
        if (!Float.isFinite(targetGamma)) throw new IllegalArgumentException("Invalid cube target gamma");
        this.id = id;
        this.settings = settings;
        this.targetGamma = targetGamma;
        this.forceLinearGamma = forceLinearGamma;
        configure(settings, 6);
    }

    @Override public RenderTargetTextureDefinition settings() { return settings; }
    @Override public Dimension dimension() { return Dimension.CUBE; }
    @Override public int extentZ() { return 6; }
    @Override public boolean closed() { return closed; }
    @Override public boolean srgbFor(String pixelFormat) {
        return targetGamma > .001f ? Math.abs(targetGamma - 1f) >= .0001f
                : !forceLinearGamma && !pixelFormat.equals("PF_FloatRGB") && !pixelFormat.equals("PF_FloatRGBA");
    }
    public CubeColorTarget image() { return Objects.requireNonNull(allocation, "Cube target was released").image; }

    private void configure(RenderTargetTextureDefinition next, int faces) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Cube target was released");
        if (faces != 6 || next.width() != next.height()) throw new IllegalArgumentException("Cube targets have six square faces");
        var previous = allocation;
        if (previous != null && settings.width() == next.width() && settings.format() == next.format()
                && settings.sourceFormat().equals(next.sourceFormat()) && settings.srgb() == next.srgb()
                && settings.filter().equals(next.filter()) && settings.mipLevels() == next.mipLevels()
                && settings.storageWritable() == next.storageWritable() && settings.sampler().equals(next.sampler())) return;
        float[] clear = next.clearColor();
        var image = new CubeColorTarget(id.toString(), next.format(), next.width(), next.mipLevels(),
                next.storageWritable(), next.srgb() && next.format() == GpuFormat.RGBA8_UNORM,
                new Vector4f(clear[0], clear[1], clear[2], clear[3]));
        GpuSampler sampler;
        try { sampler = EffectSamplerFactory.create(next.sampler(), next.mipLevels()); }
        catch (RuntimeException | Error failure) { image.close(); throw failure; }
        allocation = new Allocation(image, sampler);
        settings = next;
        if (previous != null) previous.release();
    }

    @Override public void configure(ComputeRuntime runtime, RenderTargetTextureDefinition next, int faces) {
        configure(next, faces);
    }

    @Override public Snapshot resolve() {
        RenderSystem.assertOnRenderThread();
        var current = allocation;
        return current == null ? Snapshot.UNBOUND : TextureParameter.snapshotCube(current.image.view(), current.sampler);
    }
    @Override public @Nullable Retention retain(Snapshot snapshot) {
        RenderSystem.assertOnRenderThread();
        var current = allocation;
        return current != null && snapshot.view() == current.image.view() && snapshot.sampler() == current.sampler
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
