package com.kltyton.lumenfx.client.render.niagara.gpu.texture.target;

import com.mojang.blaze3d.systems.CommandEncoder;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.cache.NiagaraGpuCacheReadbacks;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter.Dimension;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.texture.cube.CubeColorTarget;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureArray;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureVolume;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureCube;
import com.kltyton.lumenfx.client.resource.texture.target.DimensionalRenderTarget;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraDimensionalRenderTargetState.Snapshot;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Owns writable array and volume targets and retains each queued step's exact allocation. */
public final class NiagaraGpuDimensionalRenderTargets implements AutoCloseable {
    private static final class Capture {
        final Snapshot control;
        final TextureParameter.Snapshot texture;
        final @Nullable CubeColorTarget cube;
        final TextureParameter.@Nullable Retention lease;
        private int references = 1;

        Capture(Snapshot control, @Nullable DimensionalRenderTarget target) {
            this.control = control;
            texture = target == null ? TextureParameter.Snapshot.UNBOUND : target.resolve();
            cube = target instanceof RenderTargetTextureCube source ? source.image() : null;
            lease = target == null ? null : Objects.requireNonNull(target.retain(texture), "Render target array lease was unavailable");
        }

        void retain() { references = Math.incrementExact(references); }
        void release() { if (--references == 0 && lease != null) lease.close(); }
    }

    private static final class Entry {
        @Nullable DimensionalRenderTarget owned;
        @Nullable Capture capture;
        @Nullable NiagaraCacheVolumeFrame cachedVolume;
        @Nullable ComputeVolume cachedImage;

        void replace(@Nullable DimensionalRenderTarget nextOwned, @Nullable Capture next) {
            var previousCapture = capture;
            var previousOwned = owned;
            capture = next;
            owned = nextOwned;
            try { if (previousCapture != null) previousCapture.release(); }
            finally { if (previousOwned != null && previousOwned != nextOwned) previousOwned.close(); }
        }
    }

    public static final class Step implements AutoCloseable {
        private final Map<String, Capture> captures;
        private boolean closed;

        private Step(Map<String, Capture> captures) { this.captures = captures; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            captures.values().forEach(Capture::release);
        }
    }

    private final ComputeRuntime runtime;
    private @Nullable NiagaraGpuPrograms programs;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private @Nullable Step active;
    private @Nullable ComputeTexture empty;
    private @Nullable ComputeVolume emptyVolume;

    public NiagaraGpuDimensionalRenderTargets(NiagaraGpuPrograms programs) {
        this(programs.runtime());
        this.programs = programs;
    }

    public NiagaraGpuDimensionalRenderTargets(ComputeRuntime runtime) { this.runtime = runtime; }

    public void gpuPrograms(NiagaraGpuPrograms value) {
        if (programs != null && programs != value) throw new IllegalStateException("Render target programs changed during a system lifetime");
        programs = value;
    }
    public boolean hasImage(String id) { return exposed(id).bound(); }

    public void prepare(String id, Snapshot control) {
        var entry = entries.computeIfAbsent(id, ignored -> new Entry());
        var user = control.userTarget();
        DimensionalRenderTarget owned = null;
        if (user != null && !user.closed()) user.configure(runtime, control.surface(), control.extentZ());
        else if (!control.inheritUserSettings()) {
            owned = entry.owned;
            if (owned == null) {
                owned = switch (control.dimension()) {
                    case VOLUME -> new RenderTargetTextureVolume(Identifier.fromNamespaceAndPath("lumenfx", "niagara_volume_target"),
                            control.surface(), control.extentZ(), 0, true);
                    case CUBE -> new RenderTargetTextureCube(Identifier.fromNamespaceAndPath("lumenfx", "niagara_cube_target"),
                            control.surface(), 0, true);
                    case ARRAY -> new RenderTargetTextureArray(Identifier.fromNamespaceAndPath("lumenfx", "niagara_array_target"),
                            runtime, control.surface(), control.extentZ(), false);
                    case D2, CUBE_ARRAY -> throw new IllegalArgumentException("No Niagara dimensional render target for " + control.dimension());
                };
            } else owned.configure(runtime, control.surface(), control.extentZ());
        }
        Capture captured;
        try {
            captured = new Capture(control, user != null && !user.closed() ? user : owned);
        } catch (RuntimeException | Error failure) {
            if (owned != null && owned != entry.owned) owned.close();
            throw failure;
        }
        entry.replace(owned, captured);
    }

    public void restoreCache(CommandEncoder encoder, String id, Snapshot control, NiagaraCacheVolumeFrame frame) {
        prepare(id, control);
        var entry = Objects.requireNonNull(entries.get(id));
        var capture = Objects.requireNonNull(entry.capture);
        var image = capture.texture.volume();
        if (image == null || !frame.writePixels() || entry.cachedImage == image && entry.cachedVolume == frame) return;
        image.restoreCache(encoder, frame);
        entry.cachedImage = image;
        entry.cachedVolume = frame;
    }

    public CompletableFuture<NiagaraCacheVolumeFrame> captureVolumeCache(CommandEncoder encoder, @Nullable ClientLevel world,
                                                                         String id, boolean omitZeroTiles) {
        var entry = entries.get(id);
        var capture = entry == null ? null : entry.capture;
        var image = capture == null ? null : capture.texture.volume();
        if (image == null) return CompletableFuture.completedFuture(NiagaraCacheVolumeFrame.ABSENT);
        return NiagaraGpuCacheReadbacks.volume(encoder, world, id, image, capture.control.format(), omitZeroTiles);
    }

    public Step captureStep() {
        var captures = new LinkedHashMap<String, Capture>();
        try {
            entries.forEach((id, entry) -> {
                var capture = entry.capture;
                if (capture == null) return;
                capture.retain();
                captures.put(id, capture);
            });
            return new Step(captures);
        } catch (RuntimeException | Error failure) {
            captures.values().forEach(Capture::release);
            throw failure;
        }
    }

    public void beginStep(Step step) {
        if (active != null || step.closed) throw new IllegalStateException("Invalid render target array dispatch step");
        active = step;
    }
    public void endStep() { active = null; }

    public Snapshot control(String id) { return capture(id).control; }

    public byte[] size(String id) {
        var control = control(id);
        if (control.dimension() == Dimension.CUBE)
            return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(control.width()).array();
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(control.width()).putInt(control.height()).putInt(control.extentZ()).array();
    }
    public byte[] mips(String id) {
        int levels = Math.max(1, capture(id).texture.mipLevels());
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(levels).array();
    }

    public TextureParameter.Snapshot exposed(String id) {
        var entry = entries.get(id);
        return entry == null || entry.capture == null ? TextureParameter.Snapshot.UNBOUND : entry.capture.texture;
    }

    public void bind(String id, String symbol, ShaderProgram shader, ComputeBindings.Builder resources) {
        var gpu = Objects.requireNonNull(programs, "Render target array dispatch requires GPU programs");
        var publication = capture(id);
        var texture = publication.texture;
        var image = texture.arrayTexture();
        var volume = texture.volume();
        boolean volumeTarget = publication.control.dimension() == Dimension.VOLUME;
        boolean cubeTarget = publication.control.dimension() == Dimension.CUBE;
        String outputName = symbol + "_RWTexture", inputName = symbol + "_Texture";
        boolean writes = shader.bindings().stream().anyMatch(binding -> binding instanceof ShaderBinding.StorageImage
                && binding.name().equals(outputName));
        for (var binding : shader.bindings()) {
            if (binding instanceof ShaderBinding.StorageImage output && output.name().equals(outputName)) {
                if (output.volume() != volumeTarget || output.array() == volumeTarget)
                    throw new IllegalArgumentException("Invalid dimensional render target output " + output.name());
                if (volumeTarget) {
                    if (volume == null && emptyVolume == null) emptyVolume = ComputeVolume.createTarget("Niagara.EmptyVolumeTarget",
                            1, 1, 1, GpuFormat.RGBA16_UNORM, true, false, new float[4],
                            new ComputeVolume.Sampling(publication.control.sampler(), TextureMetadata.Address.WRAP));
                    resources.volumeImage(output.name(), volume == null ? Objects.requireNonNull(emptyVolume) : volume, output.access());
                } else if (publication.cube != null) {
                    resources.cubeImage(output.name(), publication.cube, output.access());
                } else {
                    if (image == null && empty == null)
                        empty = runtime.createZeroTexture("Niagara.EmptyRenderTargetArray", GpuFormat.RGBA16_UNORM, true);
                    resources.image(output.name(), image == null ? Objects.requireNonNull(empty) : image, output.access());
                }
            } else if (binding instanceof ShaderBinding.SampledTexture input
                    && (input.name().equals(inputName) || input.name().startsWith(inputName + "_"))) {
                if (input.volume() != volumeTarget || input.array() != (!volumeTarget && !cubeTarget) || input.cube() != cubeTarget)
                    throw new IllegalArgumentException("Invalid dimensional render target input " + input.name());
                if (volumeTarget) {
                    resources.volume(input.name(), writes || volume == null ? gpu.blackVolumeTexture() : volume, 0);
                    continue;
                }
                if (cubeTarget) {
                    var view = writes || texture.view() == null ? gpu.blackCubeTexture() : texture.view();
                    if (input.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.sampledImage(input.name(), view);
                    else resources.texture(input.name(), view, texture.sampler() == null
                            ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) : texture.sampler());
                    continue;
                }
                var selected = writes || image == null ? gpu.blackArrayTexture() : image;
                if (input.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.fullArraySampledImage(input.name(), selected);
                else resources.fullArrayTexture(input.name(), selected, texture.sampler() == null
                        ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) : texture.sampler());
            }
        }
    }

    private Capture capture(String id) {
        var step = active;
        var entry = entries.get(id);
        var capture = step == null ? entry == null ? null : entry.capture : step.captures.get(id);
        return Objects.requireNonNull(capture, "Render target array has no GPU publication: " + id);
    }

    public void reset() {
        active = null;
        try { entries.values().forEach(entry -> entry.replace(null, null)); }
        finally { entries.clear(); }
    }

    @Override public void close() {
        try { reset(); }
        finally {
            try { if (empty != null) { empty.close(); empty = null; } }
            finally { if (emptyVolume != null) { emptyVolume.close(); emptyVolume = null; } }
        }
    }
}
