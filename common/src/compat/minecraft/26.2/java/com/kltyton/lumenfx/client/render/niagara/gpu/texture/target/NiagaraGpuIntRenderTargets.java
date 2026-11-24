package com.kltyton.lumenfx.client.render.niagara.gpu.texture.target;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraIntRenderTarget2D.Snapshot;
import com.mojang.blaze3d.GpuFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Integer UAV allocation follows the system DI identity and keeps user textures as the actual target. */
public final class NiagaraGpuIntRenderTargets implements AutoCloseable {
    private static final Settings SAMPLER = new Settings(Address.CLAMP, Address.CLAMP, FilterMode.POINT, FilterMode.POINT, 1);
    public record IntTargetPublication(int width, int height, @Nullable ComputeTexture image,
                              TextureParameter.@Nullable Retention retention) implements AutoCloseable {
        @Override public void close() { if (retention != null) retention.close(); }
    }

    private record Target(RenderTargetTexture value, boolean owned) {
        void release() { if (owned) value.close(); }
    }
    private final Map<String, Target> targets = new HashMap<>();
    private final ComputeRuntime runtime;
    private @Nullable ComputeTexture empty;

    public NiagaraGpuIntRenderTargets(ComputeRuntime runtime) {
        if (!runtime.supportsSignedImage2D())
            throw new IllegalStateException("The render backend does not provide signed 2D image atomics for Niagara");
        this.runtime = runtime;
    }

    private void prepare(String identity, Snapshot state) {
        if (state.width() < 1 || state.height() < 1) return;
        var previous = targets.get(identity);
        var supplied = state.target();
        if (supplied != null && supplied.isClosed()) supplied = null;
        Target next;
        if (supplied != null) {
            var desired = definition(state.width(), state.height(), supplied.settings().clearColor());
            supplied.configureStorage(runtime, desired);
            next = new Target(supplied, false);
        } else if (previous != null && previous.owned()) {
            var desired = definition(state.width(), state.height(), previous.value().settings().clearColor());
            previous.value().configureStorage(runtime, desired);
            return;
        } else {
            next = new Target(new RenderTargetTexture(Identifier.fromNamespaceAndPath("lumenfx", "niagara_int_render_target"),
                    definition(state.width(), state.height(), new float[4]), null), true);
        }
        targets.put(identity, next);
        if (previous != null && previous.value() != next.value()) previous.release();
    }

    public IntTargetPublication capture(String identity, Snapshot state) {
        prepare(identity, state);
        var target = targets.get(identity);
        if (target == null || target.value().isClosed()) return new IntTargetPublication(state.width(), state.height(), null, null);
        var texture = target.value();
        var image = Objects.requireNonNull(texture.storageImage());
        var retention = Objects.requireNonNull(texture.retainStorage(), "Integer render target lease was unavailable");
        return new IntTargetPublication(state.width(), state.height(), image, retention);
    }

    private static RenderTargetTextureDefinition definition(int width, int height, float[] clear) {
        return new RenderTargetTextureDefinition(width, height, GpuFormat.R32_SINT, "PF_R32_SINT",
                false, false, clear, SAMPLER, SAMPLER, RenderTargetTextureDefinition.SIGNED_INT, true, true, "TF_Nearest");
    }

    ComputeTexture fallback() {
        if (empty == null) {
            var fallback = runtime.createColorTarget2D("Niagara.IntRenderTarget2D.EmptyUAV", GpuFormat.R32_SINT, 1, 1, 1, false);
            try { runtime.clearSignedImage2D(fallback, 0); }
            catch (RuntimeException | Error failure) { fallback.close(); throw failure; }
            empty = fallback;
        }
        return empty;
    }

    public TextureParameter.Snapshot exposed(String identity) {
        var target = targets.get(identity);
        return target == null || target.value().isClosed()
                || !SampledTextureFormats.floatingColor(target.value().settings().format())
                ? TextureParameter.Snapshot.UNBOUND : target.value().snapshot();
    }

    @Override public void close() {
        targets.values().forEach(Target::release);
        targets.clear();
        if (empty != null) { empty.close(); empty = null; }
    }
}
