package com.kltyton.lumenfx.client.simulation.niagara.texture.target;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTexture;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** CPU sizing and user target selection for the source R32_SINT render target. */
public final class NiagaraIntRenderTarget2D {
    public record Definition(int width, int height, String parameter) {
        public static Definition read(JsonObject value) {
            return new Definition(value.get("width").getAsInt(), value.get("height").getAsInt(),
                    value.get("user_parameter").getAsString());
        }
    }
    public record Snapshot(int width, int height, @Nullable RenderTargetTexture target) { }

    private final Definition definition;
    private final int maximum;
    private volatile Snapshot current;
    private boolean initialized;

    public NiagaraIntRenderTarget2D(Definition definition, int maximum, NiagaraPlatformEnvironment environment, boolean gpu) {
        this.definition = definition;
        this.maximum = maximum;
        current = new Snapshot(0, 0, null);
        initialize(environment, gpu);
    }

    public synchronized void initialize(NiagaraPlatformEnvironment environment, boolean gpu) {
        if (initialized || !gpu && environment.renderTargetIgnoreCookedOut()) return;
        float multiplier = environment.renderTargetResolutionMultiplier();
        current = new Snapshot(dimension(definition.width(), multiplier), dimension(definition.height(), multiplier), current.target());
        initialized = true;
    }

    public Snapshot snapshot() { return current; }

    public synchronized boolean setSize(int count, int width, int height, NiagaraPlatformEnvironment environment) {
        if (count != 1 || width < 0 || height < 0) return false;
        float multiplier = environment.renderTargetResolutionMultiplier();
        current = new Snapshot(dimension(width, multiplier), dimension(height, multiplier), current.target());
        return true;
    }

    public synchronized void captureTarget(Map<String, TextureParameter> inputs, @Nullable NiagaraParameterStore parameters) {
        String name = definition.parameter();
        if (name.isEmpty() || SourceNames.equal(name, "None")) return;
        var binding = parameters == null ? null : parameters.objectBinding(name);
        TextureParameter value = binding != null && binding.valid() ? NiagaraParameterStoreSources.texture(binding.value()) : null;
        if (value == null) for (var entry : inputs.entrySet()) if (SourceNames.equal(entry.getKey(), name)) {
            value = entry.getValue();
            break;
        }
        var target = value instanceof RenderTargetTexture texture ? texture
                : value instanceof TextureParameter.Resource resource ? EffectTextures.target(resource.id()) : null;
        if (target != null && target != current.target()) current = new Snapshot(current.width(), current.height(), target);
    }

    private int dimension(int size, float multiplier) {
        return Math.max(1, Math.min(maximum, SourceFloatMath.truncToInt(size * multiplier)));
    }
}
