package com.kltyton.lumenfx.client.simulation.niagara.texture.target;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** CPU control state is independent of the render thread's allocation and pending stage writes. */
public final class NiagaraRenderTarget2DState {
    public record Snapshot(int width, int height, int format, String filter, String mipGeneration,
                           String mipGenerationType, boolean inheritUserSettings, String userParameter,
                           boolean released, long revision, EffectSamplerFactory.Settings sampler,
                           @Nullable RenderTargetTexture target) { }
    private final JsonObject definition;
    private final int maximum;
    private float resolutionMultiplier;
    private String filter, mipGeneration, mipGenerationType;
    private final String baseMipGeneration, baseMipGenerationType, userParameter;
    private final @Nullable Identifier defaultTarget;
    private EffectSamplerFactory.Settings sampler;
    private @Nullable RenderTargetTexture target;
    private final boolean inheritUserSettings;
    private int width, height, format, actualMipLevels;
    private boolean released;
    private long revision = 1;

    public NiagaraRenderTarget2DState(JsonObject definition, int maximum, float resolutionMultiplier, int projectFormat) {
        this(definition, maximum, resolutionMultiplier, projectFormat, -1);
    }
    public NiagaraRenderTarget2DState(JsonObject definition, int maximum, float resolutionMultiplier, int projectFormat, int overrideFormat) {
        if (maximum < 1) throw new IllegalArgumentException("Render target requires a positive device extent");
        this.definition = definition.deepCopy(); this.maximum = maximum; this.resolutionMultiplier = resolutionMultiplier;
        width = dimension(definition.get("width").getAsInt()); height = dimension(definition.get("height").getAsInt());
        int localFormat = definition.get("override_format").getAsBoolean() ? format(definition.get("format").getAsString()) : projectFormat;
        format = overrideFormat >= 0 ? overrideFormat : localFormat;
        filter = definition.get("filter").getAsString(); mipGeneration = definition.get("mip_generation").getAsString();
        mipGenerationType = definition.get("mip_generation_type").getAsString();
        if (!java.util.Set.of("Disabled", "PostStage", "PostSimulate").contains(mipGeneration))
            throw new IllegalArgumentException("Invalid render target mip generation mode " + mipGeneration);
        if (!java.util.Set.of("Unfiltered", "Linear", "Blur1", "Blur2", "Blur3", "Blur4").contains(mipGenerationType))
            throw new IllegalArgumentException("Invalid render target mip filter " + mipGenerationType);
        inheritUserSettings = definition.get("inherit_user_settings").getAsBoolean();
        userParameter = definition.get("user_parameter").getAsString();
        baseMipGeneration = mipGeneration; baseMipGenerationType = mipGenerationType;
        sampler = EffectSamplerFactory.read(definition.getAsJsonObject("sampler"));
        defaultTarget = definition.has("user_texture") && !definition.get("user_texture").isJsonNull()
                ? Identifier.parse(definition.get("user_texture").getAsString()) : null;
    }
    public boolean matches(JsonObject value) { return definition.equals(value); }
    public synchronized void resolutionMultiplier(float value) { resolutionMultiplier = value; }
    public synchronized void initializeFormat(boolean[] supported) {
        format = supportedFormat(format, supported);
    }

    static int supportedFormat(int selected, boolean[] supported) {
        int[] next = {4, 5, 6, 6, 7, 8, 9, 9, 9, 9, 9};
        while (!supported[java.util.Objects.checkIndex(selected, supported.length)]) {
            int replacement = next[selected];
            if (replacement == selected) throw new IllegalStateException("No render target format supports typed image stores");
            selected = replacement;
        }
        return selected;
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(width, height, format, filter, mipGeneration, mipGenerationType, inheritUserSettings,
                userParameter, released, revision, sampler, target);
    }

    /** The owner thread captures UObject identity and inherited settings before any VM work. */
    public synchronized void captureTarget(Map<String, TextureParameter> parameters) {
        captureTarget(parameters, null);
    }
    public synchronized void captureTarget(Map<String, TextureParameter> parameters, @Nullable NiagaraParameterStore instance) {
        if (released) return;
        if (defaultTarget == null && (userParameter.isEmpty() || userParameter.equals("None"))) {
            if (inheritUserSettings) com.mojang.logging.LogUtils.getLogger().error("RenderTarget user parameter is required but invalid: {}", userParameter);
            return;
        }
        RenderSystem.assertOnRenderThread();
        TextureParameter parameter = null;
        if (instance != null) {
            var binding = instance.objectBinding(userParameter);
            if (binding.valid()) parameter = NiagaraParameterStoreSources.texture(binding.value());
        }
        if (parameter == null) {
            for (var entry : parameters.entrySet()) {
                if (!SourceNames.equal(entry.getKey(), userParameter)) continue;
                if (parameter != null) throw new IllegalArgumentException("Duplicate render target user parameter " + userParameter);
                parameter = entry.getValue();
            }
        }
        RenderTargetTexture selected = parameter instanceof RenderTargetTexture resource ? resource
                : parameter instanceof TextureParameter.Resource resource ? EffectTextures.target(resource.id())
                : parameter == null && defaultTarget != null ? EffectTextures.target(defaultTarget) : null;
        if (selected != null) target = selected;
        if (target != null) actualMipLevels = target.settings().mipLevels();
        if (!inheritUserSettings) return;
        if (selected == null) {
            com.mojang.logging.LogUtils.getLogger().error("RenderTarget user parameter is required but invalid: {}", userParameter);
            return;
        }
        var settings = selected.settings();
        width = settings.width(); height = settings.height(); format = settings.targetFormat(); filter = settings.filter();
        sampler = settings.sampler();
        mipGeneration = settings.autoMips() ? baseMipGeneration.equals("Disabled") ? "PostStage" : baseMipGeneration : "Disabled";
        mipGenerationType = settings.autoMips() ? baseMipGenerationType : "Unfiltered";
        actualMipLevels = settings.mipLevels(); revision++;
    }
    public synchronized boolean setSize(int instances, int x, int y, boolean execute) {
        boolean success = instances == 1 && x >= 0 && y >= 0 && execute;
        if (success) { released = false; width = dimension(x); height = dimension(y); revision++; }
        return success;
    }
    public synchronized void release(boolean execute) {
        if (execute) { released = true; width = height = 1; target = null; revision++; }
    }
    public synchronized void readCache(int x, int y, int cachedFormat) {
        if (x == 0 && y == 0) {
            release(true);
            return;
        }
        if (x < 1 || y < 1 || x > maximum || y > maximum)
            throw new IllegalArgumentException("Recorded Niagara render target exceeds the device extent");
        if (released || width != x || height != y || format != cachedFormat || !filter.equals("Default")) {
            released = false; width = x; height = y; format = cachedFormat; filter = "Default";
            revision++;
        }
    }
    public synchronized boolean setFormat(int instances, int value) {
        if (instances != 1) return false;
        format = value; revision++;
        return true;
    }
    public synchronized int mipLevels() {
        return actualMipLevels > 0 ? actualMipLevels : mipGeneration.equals("Disabled") ? 1
                : 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }
    public synchronized void allocatedMipLevels(int levels) {
        if (levels < 0) throw new IllegalArgumentException("Negative allocated mip count");
        actualMipLevels = levels;
    }

    public @Nullable RenderTargetTexture applyUserControls(com.kltyton.lumenfx.client.render.compute.ComputeRuntime runtime, Snapshot control) {
        RenderSystem.assertOnRenderThread();
        if (control.released() || control.target() == null) return null;
        var target = control.target();
        var desired = target.settings().forNiagara(control.width(), control.height(), control.format(),
                !control.mipGeneration().equals("Disabled"), control.filter());
        target.configureStorage(runtime, desired);
        allocatedMipLevels(desired.mipLevels());
        return target;
    }
    private int dimension(int size) {
        int scaled = com.kltyton.lumenfx.client.render.math.UnrealRendererMath.truncToInt((float)size * resolutionMultiplier);
        return Math.max(1, Math.min(maximum, scaled));
    }
    static int format(String value) {
        return RenderTargetTextureDefinition.targetFormat(value);
    }
}
