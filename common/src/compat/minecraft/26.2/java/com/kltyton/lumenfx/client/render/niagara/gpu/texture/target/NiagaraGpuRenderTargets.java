package com.kltyton.lumenfx.client.render.niagara.gpu.texture.target;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraRenderTarget2DState;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Target2D;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** One system owns each physical DI image; emitter bindings borrow it by source object identity. */
public final class NiagaraGpuRenderTargets implements AutoCloseable {
    private static final class ImageAllocation implements AutoCloseable {
        final ComputeTexture image;
        final GpuSampler sampler;
        private int references = 1;

        ImageAllocation(ComputeTexture image, GpuSampler sampler) {
            this.image = image;
            this.sampler = sampler;
        }

        ImageAllocation retain() {
            references = Math.incrementExact(references);
            return this;
        }

        @Override public void close() {
            if (--references == 0) {
                try { image.close(); }
                finally { sampler.close(); }
            }
        }
    }

    private static final class Entry implements AutoCloseable {
        final NiagaraRenderTarget2DState source;
        NiagaraRenderTarget2DState.@Nullable Snapshot control;
        @Nullable ImageAllocation allocation;
        @Nullable ComputeTexture externalOwner;
        @Nullable RenderTargetTexture external;
        EffectSamplerFactory.@Nullable Settings sampling;
        @Nullable Target2D cachedFrame;
        boolean dirty, read, wrote, mipSrgb;
        Entry(NiagaraRenderTarget2DState source) { this.source = source; }
        @Nullable ComputeTexture image() { return allocation == null ? null : allocation.image; }
        Entry copyForStep() {
            var copy = new Entry(source);
            copy.control = control;
            copy.allocation = allocation == null ? null : allocation.retain();
            copy.externalOwner = externalOwner;
            copy.external = external;
            copy.mipSrgb = mipSrgb;
            return copy;
        }
        void releaseImage() {
            if (allocation != null) allocation.close();
            allocation = null; externalOwner = null; sampling = null; cachedFrame = null;
        }
        @Override public void close() {
            releaseImage(); external = null;
            source.allocatedMipLevels(0);
        }
    }

    /** A completed host step retains the exact allocations selected before its GPU work. */
    public static final class Step implements AutoCloseable {
        private final Map<String, Entry> targets = new LinkedHashMap<>();
        private Step() { }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            targets.values().forEach(Entry::releaseImage);
            targets.clear();
        }
    }

    private final ComputeRuntime runtime;
    private @Nullable NiagaraGpuPrograms programs;
    private final NiagaraInterfaces interfaces;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private @Nullable Step activeStep;
    private @Nullable ComputeTexture empty;
    private boolean closed;

    public NiagaraGpuRenderTargets(NiagaraGpuPrograms programs, NiagaraInterfaces interfaces) {
        this(programs.runtime(), interfaces);
        this.programs = programs;
    }

    public NiagaraGpuRenderTargets(ComputeRuntime runtime, NiagaraInterfaces interfaces) {
        this.runtime = runtime;
        this.interfaces = interfaces;
        interfaces.renderTargetExposure(this::exposed);
    }

    public void gpuPrograms(NiagaraGpuPrograms value) {
        if (programs != null && programs != value) throw new IllegalStateException("Render target programs changed during a system lifetime");
        programs = value;
    }

    public boolean hasImage(String id) {
        var entry = entries.get(id);
        return entry != null && entry.image() != null;
    }

    public void register(JsonObject binding) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara render targets have been released");
        String id = binding.get("interface_id").getAsString();
        var source = interfaces.registerRenderTarget2D(id, Objects.requireNonNull(binding.getAsJsonObject("render_target_2d")),
                runtime.maxTexture2D());
        entries.computeIfAbsent(id, ignored -> new Entry(source));
    }

    public void register(String id) {
        entries.computeIfAbsent(id, ignored -> new Entry(interfaces.renderTarget2D(id)));
    }

    /** A newly selected DI already owns its GT state; its GPU image is acquired only on the render thread. */
    public void prepareSelected(String id) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara render targets have been released");
        if (activeStep != null) {
            entry(id);
            return;
        }
        Entry entry = entries.computeIfAbsent(id, ignored -> new Entry(interfaces.renderTarget2D(id)));
        prepare(id, entry.source.snapshot());
    }

    public void restoreCache(CommandEncoder encoder, String id, Target2D saved) {
        register(id);
        Entry entry = liveEntry(id);
        var state = entry.source.snapshot();
        prepare(id, state);
        var image = entry.image();
        var pixels = saved.pixelData();
        if (image == null || !pixels.hasRemaining() || entry.cachedFrame == saved) return;
        if (image.width() < saved.width() || image.height() < saved.height()
                || image.format() != RenderTargetTextureDefinition.gpuFormat(saved.format()))
            throw new IllegalArgumentException("Live target cannot receive the Niagara cache pixels: " + id);
        GpuBufferSlice upload;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(pixels.remaining(), Integer.BYTES, GpuBuffer.USAGE_COPY_SRC)) {
            mapped.data().put(pixels);
            upload = mapped.slice();
        }
        encoder.copyBufferToTexture(upload, 0, 0, saved.width(), saved.height(), image.texture(),
                0, 0, saved.width(), saved.height(), 0, 0);
        if (entry.external != null) entry.external.storageWritesCompleted();
        entry.cachedFrame = saved;
    }

    public CompletableFuture<Target2D> captureCache(CommandEncoder encoder, @Nullable ClientLevel world, String id) {
        var entry = entries.get(id);
        var image = entry == null ? null : entry.image();
        if (image == null) return CompletableFuture.completedFuture(new Target2D(2, 0, 0, new byte[0]));
        var control = Objects.requireNonNull(entry.control);
        return NiagaraGpuCacheReadbacks.target2D(encoder, world, id, image.texture(), control.format());
    }

    public void prepare(String id, NiagaraRenderTarget2DState.Snapshot control) {
        RenderSystem.assertOnRenderThread();
        Entry entry = liveEntry(id);
        entry.control = control;
        if (control.released()) {
            entry.close(); entry.dirty = entry.read = entry.wrote = false;
            return;
        }
        if (control.target() != null) {
            var target = Objects.requireNonNull(entry.source.applyUserControls(runtime, control));
            var snapshot = target.snapshot();
            var owner = Objects.requireNonNull(target.storageImage());
            var sampling = target.settings().sampler();
            entry.mipSrgb = target.settings().srgb();
            if (entry.externalOwner != owner || entry.allocation == null || !sampling.equals(entry.sampling)) {
                var retained = owner.retain();
                GpuSampler sampler = null;
                try { sampler = EffectSamplerFactory.create(sampling, retained.mips()); }
                catch (RuntimeException | Error failure) { retained.close(); throw failure; }
                entry.releaseImage();
                entry.allocation = new ImageAllocation(retained, sampler);
                entry.externalOwner = owner; entry.sampling = sampling;
                entry.dirty = false;
            }
            entry.external = target;
            entry.source.allocatedMipLevels(snapshot.mipLevels());
            return;
        }
        entry.external = null;
        if (control.inheritUserSettings()) {
            entry.releaseImage();
            entry.source.allocatedMipLevels(0);
            return;
        }
        GpuFormat format = RenderTargetTextureDefinition.gpuFormat(control.format());
        entry.mipSrgb = control.format() == 3;
        int mips = control.mipGeneration().equals("Disabled") ? 1
                : 32 - Integer.numberOfLeadingZeros(Math.max(control.width(), control.height()));
        var owned = entry.externalOwner == null ? entry.image() : null;
        if (owned == null || owned.width() != control.width() || owned.height() != control.height()
                || owned.format() != format || owned.srgb() != (control.format() == 3)
                || owned.mips() != mips || !control.sampler().equals(entry.sampling)) {
            ComputeTexture created = runtime.createColorTarget2D("Niagara.RenderTarget2D." + id,
                    format, control.width(), control.height(), mips, control.format() == 3);
            GpuSampler sampler = null;
            try {
                sampler = EffectSamplerFactory.create(control.sampler(), mips);
                runtime.clearColor2D(created);
            } catch (RuntimeException | Error failure) {
                if (sampler != null) sampler.close();
                created.close(); throw failure;
            }
            entry.releaseImage();
            entry.allocation = new ImageAllocation(created, sampler);
            entry.sampling = control.sampler();
            entry.dirty = false;
        }
        entry.source.allocatedMipLevels(mips);
    }

    public Step captureStep() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara render targets have been released");
        Step step = new Step();
        try {
            entries.forEach((id, entry) -> {
                if (entry.control != null) step.targets.put(id, entry.copyForStep());
            });
            return step;
        } catch (RuntimeException | Error failure) {
            step.close();
            throw failure;
        }
    }

    public void beginStep(Step step) {
        RenderSystem.assertOnRenderThread();
        if (closed || activeStep != null) throw new IllegalStateException("Niagara render-target step is already active or released");
        activeStep = Objects.requireNonNull(step);
    }

    public void endStep() { activeStep = null; }

    public NiagaraRenderTarget2DState.Snapshot control(String id) { return Objects.requireNonNull(entry(id).control); }
    public NiagaraRenderTarget2DState.Snapshot sourceDefaultSnapshot(String id) {
        Entry entry = liveEntry(id);
        entry.source.captureTarget(Map.of());
        return entry.source.snapshot();
    }
    public byte[] size(String id) { var value = control(id); return integers(value.width(), value.height()); }
    public byte[] mips(String id) { var image = entry(id).image(); return integers(image == null ? 1 : image.mips()); }

    public void bind(String id, String symbol, ShaderProgram shader, ComputeBindings.Builder bindings) {
        Entry entry = entry(id);
        var image = entry.image();
        boolean write = shader.bindings().stream().anyMatch(binding -> binding instanceof ShaderBinding.StorageImage
                && binding.name().equals(symbol + "_RWTexture"));
        for (var binding : shader.bindings()) {
            if (binding instanceof ShaderBinding.StorageImage output && output.name().equals(symbol + "_RWTexture")) {
                if (image == null && empty == null)
                    empty = runtime.createZeroTexture("Niagara.EmptyRenderTarget2D", GpuFormat.RGBA16_UNORM, false);
                bindings.image(output.name(), image == null ? Objects.requireNonNull(empty) : image, output.access());
                if (image != null) { entry.dirty = true; entry.wrote = true; }
            } else if (binding instanceof ShaderBinding.SampledTexture input && (input.name().equals(symbol + "_Texture")
                    || input.name().startsWith(symbol + "_Texture_"))) {
                var view = !write && image != null ? image.sampledView() : Objects.requireNonNull(programs).blackTexture();
                var sampler = entry.allocation == null
                        ? RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST) : entry.allocation.sampler;
                if (input.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) bindings.sampledImage(input.name(), view);
                else bindings.texture(input.name(), view, sampler);
                if (!write && image != null) entry.read = true;
            }
        }
    }

    public void postStage(CommandEncoder encoder, String id) {
        Entry entry = entry(id);
        if (entry.dirty && control(id).mipGeneration().equals("PostStage")) generate(encoder, entry);
    }

    public void postSimulate(CommandEncoder encoder, String id) {
        Entry entry = entry(id);
        if (entry.dirty && control(id).mipGeneration().equals("PostSimulate")) generate(encoder, entry);
        if (entry.wrote && entry.external != null && !entry.external.isClosed()
                && entry.external.storageImage() == entry.externalOwner) entry.external.storageWritesCompleted();
        entry.dirty = entry.read = entry.wrote = false;
    }

    public TextureParameter.Snapshot exposed(String id) {
        RenderSystem.assertOnRenderThread();
        Entry entry = entries.get(id);
        var allocation = entry == null ? null : entry.allocation;
        return allocation == null ? TextureParameter.Snapshot.UNBOUND
                : TextureParameter.snapshot(allocation.image.sampledView(), allocation.sampler);
    }

    private void generate(CommandEncoder encoder, Entry entry) {
        var image = entry.image();
        if (image != null)
            NiagaraRenderTargetMipGenerator.generate(Objects.requireNonNull(programs), encoder, image,
                    Objects.requireNonNull(entry.control).mipGenerationType(), entry.mipSrgb);
        entry.dirty = false;
    }
    private Entry entry(String id) {
        if (activeStep != null)
            return Objects.requireNonNull(activeStep.targets.get(id), "Render target not prepared for the queued Niagara step: " + id);
        return liveEntry(id);
    }

    private Entry liveEntry(String id) {
        if (closed) throw new IllegalStateException("Niagara render targets have been released");
        return Objects.requireNonNull(entries.get(id), "Unregistered render target " + id);
    }
    private static byte[] integers(int... values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) result.putInt(value);
        return result.array();
    }
    public void reset() {
        RenderSystem.assertOnRenderThread();
        entries.values().forEach(Entry::close); entries.clear();
    }
    @Override public void close() {
        if (closed) return;
        reset(); if (empty != null) empty.close(); empty = null; closed = true;
    }
}
