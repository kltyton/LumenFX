package com.kltyton.lumenfx.client.simulation.niagara.texture.target;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter.Dimension;
import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.target.DimensionalRenderTarget;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.logging.LogUtils;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Host-side controls for Niagara writable array and volume targets. */
public final class NiagaraDimensionalRenderTargetState {
    public record Snapshot(Dimension dimension, int width, int height, int extentZ, int format, String filter,
                           EffectSamplerFactory.Settings sampler, boolean inheritUserSettings,
                           @Nullable DimensionalRenderTarget userTarget, @Nullable RenderTargetTextureDefinition inheritedSurface) {
        public RenderTargetTextureDefinition surface() {
            var inherited = inheritedSurface;
            var gpu = inherited == null ? RenderTargetTextureDefinition.gpuFormat(format) : inherited.format();
            String pixel;
            if (inherited != null) pixel = inherited.sourceFormat();
            else if (format == 2 || format == 3) pixel = "PF_B8G8R8A8";
            else pixel = RenderTargetTextureDefinition.sourceFormat(format);
            var user = userTarget;
            boolean srgb = user != null && user.srgbFor(pixel);
            float[] clear = user == null ? new float[4] : user.settings().clearColor();
            boolean autoMips = dimension == Dimension.CUBE && user != null && user.settings().autoMips();
            var mipSampler = user == null ? sampler : user.settings().mipSampler();
            return new RenderTargetTextureDefinition(width, height, gpu, pixel, srgb, autoMips, clear, sampler, mipSampler,
                    format, true, true, filter);
        }
    }

    private final JsonObject definition;
    private final Dimension dimension;
    private final int maximum;
    private float resolutionMultiplier;
    private final String userParameter;
    private final @Nullable Identifier defaultTarget;
    private final boolean inherit;
    private int width, height, extentZ, format;
    private String filter;
    private EffectSamplerFactory.Settings sampler;
    private @Nullable DimensionalRenderTarget userTarget;
    private @Nullable RenderTargetTextureDefinition inheritedSurface;
    private boolean invalidBindingReported;

    public NiagaraDimensionalRenderTargetState(JsonObject definition, int maximum, float resolutionMultiplier,
                                               int projectFormat, int overrideFormat, boolean[] supportedFormats) {
        if (maximum < 1) throw new IllegalArgumentException("Render target array requires a positive device extent");
        this.definition = definition.deepCopy();
        if (definition.has("cube") && definition.get("cube").getAsBoolean()) dimension = Dimension.CUBE;
        else if (definition.has("volume") && definition.get("volume").getAsBoolean()) dimension = Dimension.VOLUME;
        else dimension = Dimension.ARRAY;
        this.maximum = maximum;
        this.resolutionMultiplier = resolutionMultiplier;
        width = dimension(definition.get("width").getAsInt());
        height = dimension(definition.get("height").getAsInt());
        extentZ = extentZ(definition.get("layers").getAsInt());
        int initial = definition.get("override_format").getAsBoolean()
                ? NiagaraRenderTarget2DState.format(definition.get("format").getAsString()) : projectFormat;
        format = NiagaraRenderTarget2DState.supportedFormat(overrideFormat >= 0 ? overrideFormat : initial, supportedFormats);
        filter = definition.get("filter").getAsString();
        sampler = EffectSamplerFactory.read(definition.getAsJsonObject("sampler"));
        inherit = definition.get("inherit_user_settings").getAsBoolean();
        userParameter = definition.get("user_parameter").getAsString();
        defaultTarget = definition.has("user_texture") && !definition.get("user_texture").isJsonNull()
                ? Identifier.parse(definition.get("user_texture").getAsString()) : null;
    }

    public boolean matches(JsonObject value) { return definition.equals(value); }
    public synchronized void resolutionMultiplier(float value) { resolutionMultiplier = value; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(dimension, width, height, extentZ, format, filter, sampler, inherit, userTarget, inheritedSurface);
    }

    public synchronized void captureTarget(Map<String, TextureParameter> parameters, @Nullable NiagaraParameterStore instance) {
        Object binding = null;
        boolean bound = false;
        if (instance != null && !userParameter.isEmpty() && !userParameter.equals("None")) {
            var slot = instance.objectBinding(userParameter);
            binding = slot.value();
            bound = slot.valid();
        }
        if (!bound && !userParameter.isEmpty() && !userParameter.equals("None")) {
            for (var entry : parameters.entrySet()) {
                if (!SourceNames.equal(entry.getKey(), userParameter)) continue;
                if (bound) throw new IllegalArgumentException("Duplicate render target array user parameter " + userParameter);
                binding = entry.getValue();
                bound = true;
            }
        }
        DimensionalRenderTarget selected = binding instanceof DimensionalRenderTarget target ? target
                : binding instanceof TextureParameter.Resource resource ? resourceTarget(resource.id())
                : !bound && defaultTarget != null ? resourceTarget(defaultTarget) : null;
        if (userTarget != null && userTarget.closed()) userTarget = null;
        if (selected != null && (selected.closed() || selected.dimension() != dimension)) selected = null;
        if (selected != null) userTarget = selected;
        if (!inherit) return;
        if (selected == null) {
            if (!invalidBindingReported) LogUtils.getLogger().error("Render target array user parameter is required but invalid: {}", userParameter);
            invalidBindingReported = true;
            return;
        }
        invalidBindingReported = false;
        inheritedSurface = selected.settings();
        width = inheritedSurface.width();
        height = inheritedSurface.height();
        extentZ = selected.extentZ();
        format = inheritedSurface.targetFormat();
        filter = inheritedSurface.filter();
        sampler = inheritedSurface.sampler();
    }

    public synchronized boolean setSize(int instances, int x, int y, int z) {
        if (instances != 1 || x <= 0 || y <= 0 || z <= 0) return false;
        width = dimension(x);
        height = dimension(y);
        extentZ = extentZ(z);
        return true;
    }

    public synchronized boolean setFormat(int instances, int value) {
        if (instances != 1) return false;
        RenderTargetTextureDefinition.gpuFormat(value);
        format = value;
        inheritedSurface = null;
        return true;
    }

    public synchronized void readCache(NiagaraCacheVolumeFrame frame) {
        if (dimension != Dimension.VOLUME) throw new IllegalStateException("Volume cache is bound to a different render target");
        if (frame.format() < 0) return;
        if (frame.width() > maximum || frame.height() > maximum || frame.depth() > maximum)
            throw new IllegalArgumentException("Recorded Niagara volume exceeds the render target limit");
        width = frame.width(); height = frame.height(); extentZ = frame.depth();
        format = frame.format(); filter = "TF_Default"; inheritedSurface = null;
    }

    private int dimension(int size) { return Math.clamp(UnrealRendererMath.truncToInt((float)size * resolutionMultiplier), 1, maximum); }
    private int extentZ(int size) {
        return switch (dimension) {
            case VOLUME -> dimension(size);
            case CUBE -> 6;
            default -> Math.clamp(size, 1, maximum);
        };
    }
    private @Nullable DimensionalRenderTarget resourceTarget(Identifier id) {
        return switch (dimension) {
            case VOLUME -> EffectTextures.targetVolume(id);
            case CUBE -> EffectTextures.targetCube(id);
            default -> EffectTextures.targetArray(id);
        };
    }
}
