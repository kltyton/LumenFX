package com.kltyton.lumenfx.client.resource.texture.target;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureTarget;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.format.ComputeColorViews;
import com.kltyton.lumenfx.client.render.texture.target.TextureMipPrograms;
import com.kltyton.lumenfx.client.render.texture.NativeColorFormats;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureCreation;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/** Owns the resolved color image, source sampler and mip chain of a render-target asset. */
public final class RenderTargetTexture implements TextureTarget, AutoCloseable {
    private static final AtomicLong NEXT_PIPELINE = new AtomicLong();
    private static final class Allocation implements AutoCloseable {
        final GpuTexture texture;
        final GpuTextureView sampled;
        final List<GpuTextureView> levels;
        final GpuSampler sampler;
        final @Nullable GpuSampler mipSampler;
        final @Nullable ComputeTexture storageTexture;
        private int references = 1;

        Allocation(Identifier id, RenderTargetTextureDefinition settings, @Nullable TextureMipPrograms mipPrograms) {
            this(id, settings, null, mipPrograms);
        }

        Allocation(Identifier id, RenderTargetTextureDefinition settings,
                   @Nullable ComputeRuntime runtime, @Nullable TextureMipPrograms mipPrograms) {
            var device = RenderSystem.getDevice();
            NativeTextureStorage storage = settings.storage();
            boolean automaticStorage = settings.autoMips() && settings.mipLevels() > 1 && mipPrograms != null && mipPrograms.supports(settings.format());
            if (runtime == null && mipPrograms != null && (settings.storageWritable() || automaticStorage)) runtime = mipPrograms.runtime();
            if (settings.storageWritable() || automaticStorage) {
                if (runtime == null) {
                    try (var allocationRuntime = ComputeRuntime.create(device)) {
                        storageTexture = allocationRuntime.createColorTarget2D(id.toString(), settings.format(), settings.width(),
                                settings.height(), settings.mipLevels(), settings.srgb() && settings.format() == GpuFormat.RGBA8_UNORM);
                    }
                } else storageTexture = runtime.createColorTarget2D(id.toString(), settings.format(), settings.width(), settings.height(),
                        settings.mipLevels(), settings.srgb() && settings.format() == GpuFormat.RGBA8_UNORM);
            } else storageTexture = null;
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
            texture = storageTexture != null ? storageTexture.texture()
                    : storage == null ? device.createTexture(id.toString(), usage, settings.format(), settings.width(), settings.height(), 1, settings.mipLevels())
                    : NativeTextureCreation.colorTarget(storage, () -> device.createTexture(id.toString(), usage, settings.format(), settings.width(), settings.height(), 1, settings.mipLevels()));
            GpuTextureView view = null;
            GpuSampler sampling = null, mipSampling = null;
            List<GpuTextureView> views = new ArrayList<>();
            try {
                view = storageTexture != null ? storageTexture.sampledView()
                        : storage == null ? device.createTextureView(texture) : NativeTextureCreation.colorTarget(storage, () -> device.createTextureView(texture));
                for (int level = 0; level < settings.mipLevels(); level++) {
                    final int mip = level;
                    views.add(storageTexture != null
                            ? ComputeColorViews.colorView(texture, mip, 1, storageTexture.srgb())
                            : storage == null ? device.createTextureView(texture, mip, 1)
                            : NativeTextureCreation.colorTarget(storage, () -> device.createTextureView(texture, mip, 1)));
                }
                sampling = EffectSamplerFactory.create(settings.sampler(), settings.mipLevels());
                if (settings.mipLevels() > 1) mipSampling = EffectSamplerFactory.create(settings.mipSampler(), 1);
            } catch (RuntimeException | Error error) {
                if (mipSampling != null) mipSampling.close();
                if (sampling != null) sampling.close();
                views.forEach(GpuTextureView::close);
                if (storageTexture != null) storageTexture.close();
                else { if (view != null) view.close(); texture.close(); }
                throw error;
            }
            sampled = view; levels = List.copyOf(views); sampler = sampling; mipSampler = mipSampling;
        }
        TextureParameter.Retention retain() {
            var hold = new TextureParameter.Retention(this::close);
            references = Math.incrementExact(references);
            return hold;
        }

        @Override public void close() {
            if (--references != 0) return;
            levels.forEach(GpuTextureView::close); sampler.close(); if (mipSampler != null) mipSampler.close();
            if (storageTexture != null) storageTexture.close(); else { sampled.close(); texture.close(); }
        }
    }

    private final Identifier id;
    private final @Nullable TextureMipPrograms mipPrograms;
    private @Nullable RenderPipeline mipPipeline;
    private RenderTargetTextureDefinition settings;
    private Allocation allocation;
    private boolean dirty, closed;

    public RenderTargetTexture(Identifier id, RenderTargetTextureDefinition settings) {
        this(id, settings, VfxEngine.INSTANCE.textureMipPrograms());
    }

    public RenderTargetTexture(Identifier id, RenderTargetTextureDefinition settings, @Nullable TextureMipPrograms mipPrograms) {
        RenderSystem.assertOnRenderThread();
        this.id = id; this.settings = settings;
        this.mipPrograms = mipPrograms == null ? null : mipPrograms.retain();
        Allocation created = null;
        try {
            mipPipeline = mipPipeline(settings);
            created = new Allocation(id, settings, mipPrograms);
            allocation = created;
            clear();
        } catch (RuntimeException | Error error) {
            if (created != null) created.close();
            if (this.mipPrograms != null) this.mipPrograms.releaseTarget();
            closed = true; throw error;
        }
    }

    private static @Nullable RenderPipeline mipPipeline(RenderTargetTextureDefinition settings) {
        RenderPipeline pipeline = !settings.autoMips() ? null : RenderPipeline.builder().withLocation(LumenFx.id("pipeline/texture_target_mips/" + NEXT_PIPELINE.getAndIncrement()))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/texture_target_mip"))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("MipIn").build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), settings.format(), ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
        if (pipeline != null && settings.allocationStorage() != null) NativeColorFormats.register(pipeline, settings.allocationStorage());
        return pipeline;
    }

    public Identifier id() { return id; }
    public boolean isClosed() { return closed; }
    public RenderTargetTextureDefinition settings() { ensureOpen(); return settings; }
    public @Nullable ComputeTexture storageImage() { ensureOpen(); return allocation.storageTexture; }

    public void configureStorage(ComputeRuntime runtime, RenderTargetTextureDefinition nextSettings) {
        ensureOpen();
        if (!nextSettings.storageWritable()) throw new IllegalArgumentException("Niagara render target requires writable storage");
        if (allocation.storageTexture != null && settings.width() == nextSettings.width() && settings.height() == nextSettings.height()
                && settings.format() == nextSettings.format() && settings.srgb() == nextSettings.srgb()
                && settings.targetFormat() == nextSettings.targetFormat() && settings.autoMips() == nextSettings.autoMips()
                && settings.filter().equals(nextSettings.filter()) && settings.sampler().equals(nextSettings.sampler())) return;
        var next = new Allocation(id, nextSettings, runtime, mipPrograms);
        var old = allocation; var previousSettings = settings; var previousPipeline = mipPipeline;
        allocation = next; settings = nextSettings;
        if (previousSettings.format() != nextSettings.format() || previousSettings.autoMips() != nextSettings.autoMips()
                || !Objects.equals(previousSettings.allocationStorage(), nextSettings.allocationStorage()))
            mipPipeline = mipPipeline(nextSettings);
        try { clear(); }
        catch (RuntimeException | Error error) {
            allocation = old; settings = previousSettings; mipPipeline = previousPipeline;
            next.close(); throw error;
        }
        old.close();
    }

    /** Niagara has applied its own PostStage/PostSimulate mip policy to the external image. */
    public void storageWritesCompleted() { ensureOpen(); dirty = false; }

    /** The producer closes its render pass before the image is resolved for a consumer. */
    public GpuTextureView colorAttachment() { ensureOpen(); dirty = true; return allocation.levels.getFirst(); }

    public TextureParameter.Snapshot snapshot() {
        ensureOpen();
        if (dirty) updateMips(RenderSystem.getDevice().createCommandEncoder(), allocation, settings);
        dirty = false;
        return TextureParameter.snapshot(allocation.sampled, allocation.sampler);
    }

    public TextureParameter.Snapshot preparedSnapshot() {
        ensureOpen();
        if (dirty) throw new IllegalStateException("Render target must be prepared before opening its consumer pass: " + id);
        return TextureParameter.snapshot(allocation.sampled, allocation.sampler);
    }

    @Override public TextureParameter.Snapshot resolve() { return snapshot(); }
    @Override public boolean retainsAcrossFrames() { return true; }

    @Override public TextureParameter.@Nullable Retention retain(TextureParameter.Snapshot snapshot) {
        RenderSystem.assertOnRenderThread();
        var selected = allocation;
        return closed || snapshot.view() != selected.sampled || snapshot.sampler() != selected.sampler
                ? null : selected.retain();
    }

    /** Retains the live storage allocation directly, including integer render targets that are not floating-sampled. */
    public TextureParameter.@Nullable Retention retainStorage() {
        RenderSystem.assertOnRenderThread();
        return closed || allocation.storageTexture == null ? null : allocation.retain();
    }

    public void clear() {
        ensureOpen();
        if (settings.format() == GpuFormat.R32_SINT) {
            var texture = Objects.requireNonNull(allocation.storageTexture);
            int value = (int)settings.clearColor()[0];
            if (mipPrograms != null) mipPrograms.runtime().clearSignedImage2D(texture, value);
            else try (var runtime = ComputeRuntime.create(RenderSystem.getDevice())) { runtime.clearSignedImage2D(texture, value); }
            dirty = false;
            return;
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        withColorSpace(settings, () -> {
            var color = settings.clearColor();
            try (var pass = encoder.createRenderPass(RenderPassDescriptor.create(() -> "LumenFX render target clear")
                    .withColorAttachment(allocation.levels.getFirst(), Optional.of(new Vector4f(color[0], color[1], color[2], color[3])))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, settings.width(), settings.height())))) { }
            updateMips(encoder, allocation, settings);
        });
        dirty = false;
    }

    public void resize(int width, int height) {
        ensureOpen();
        if (width == settings.width() && height == settings.height()) return;
        var nextSettings = settings.resized(width, height);
        var next = new Allocation(id, nextSettings, mipPrograms);
        var previous = allocation;
        var previousSettings = settings;
        allocation = next; settings = nextSettings;
        try { clear(); }
        catch (RuntimeException | Error error) {
            allocation = previous; settings = previousSettings;
            next.close();
            throw error;
        }
        previous.close();
    }

    private void updateMips(CommandEncoder encoder, Allocation target, RenderTargetTextureDefinition definition) {
        if (definition.mipLevels() == 1) return;
        if (target.storageTexture != null && mipPrograms != null) {
            mipPrograms.generate(encoder, target.storageTexture, Objects.requireNonNull(target.mipSampler));
            return;
        }
        withColorSpace(definition, () -> {
            for (int mip = 1; mip < target.levels.size(); mip++) {
                final int level = mip;
                var descriptor = RenderPassDescriptor.create(() -> "LumenFX render target mip " + level)
                        .withColorAttachment(target.levels.get(mip)).withRenderArea(new RenderPass.RenderArea(0, 0,
                                Math.max(1, definition.width() >> mip), Math.max(1, definition.height() >> mip)));
                try (var pass = encoder.createRenderPass(descriptor)) {
                    pass.setPipeline(Objects.requireNonNull(mipPipeline));
                    pass.bindTexture("MipIn", target.levels.get(mip - 1), Objects.requireNonNull(target.mipSampler));
                    pass.draw(3, 1, 0, 0);
                }
            }
        });
    }

    private static void withColorSpace(RenderTargetTextureDefinition settings, Runnable action) {
        boolean gl = RenderSystem.getDevice().backend instanceof com.mojang.blaze3d.opengl.GlDevice;
        boolean encoded = settings.storage() != null && settings.storage().srgb();
        boolean previous = gl && GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
        if (gl && encoded) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
        try { action.run(); }
        finally { if (gl && encoded && !previous) GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB); }
    }

    private void ensureOpen() { RenderSystem.assertOnRenderThread(); if (closed) throw new IllegalStateException("Render target texture has been released: " + id); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        try { allocation.close(); }
        finally { if (mipPrograms != null) mipPrograms.releaseTarget(); }
    }
}
