package com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraVirtualTextureInputs;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Resolves the source UObject and retains the exact page publication used by this simulation step. */
public final class NiagaraVirtualTextureState implements AutoCloseable {
    public record Frame(Vec3 tileOrigin, NiagaraRuntimeVirtualTexture.@Nullable Lease runtime,
                        NiagaraStreamingVirtualTexture.@Nullable Lease streaming) implements AutoCloseable {
        @Override public void close() {
            try { if (runtime != null) runtime.close(); }
            finally { if (streaming != null) streaming.close(); }
        }
    }

    private final boolean runtimeType;
    private final String defaultTexture, parameter;
    private @Nullable NiagaraRuntimeVirtualTexture runtime;
    private NiagaraStreamingVirtualTexture.@Nullable Lease streaming;
    private int width, height, mips;
    private Vec3 tile = Vec3.ZERO;

    public NiagaraVirtualTextureState(JsonObject source) {
        runtimeType = source.get("runtime").getAsBoolean();
        defaultTexture = source.get("texture").getAsString();
        parameter = source.get("parameter").getAsString();
    }

    public void begin(NiagaraVirtualTextureInputs inputs, Map<String, NiagaraStreamingVirtualTexture> packaged,
                      Map<String, NiagaraRuntimeVirtualTexture> placed,
                      @Nullable NiagaraParameterStore instance, Vec3 tileOrigin) {
        close();
        tile = tileOrigin;
        var binding = parameter.isEmpty() || instance == null ? null : instance.objectBinding(parameter);
        String sourcePath = binding == null || !binding.valid() ? defaultTexture
                : NiagaraParameterStoreSources.sourceObjectPath(binding.value());
        if (runtimeType) runtime = select(inputs.runtime(), placed, sourcePath);
        else {
            var texture = select(inputs.streaming(), packaged, sourcePath);
            if (texture != null) streaming = texture.acquire();
            if (streaming != null) {
                var image = streaming.snapshot();
                width = image.width(); height = image.height(); mips = image.mips();
            }
        }
    }

    private <T> @Nullable T select(Map<String, T> values, Map<String, T> packaged, String sourcePath) {
        T supplied = parameter.isEmpty() ? null : values.get(parameter);
        if (supplied != null || sourcePath.isEmpty()) return supplied;
        supplied = values.get(sourcePath);
        return supplied == null ? packaged.get(sourcePath) : supplied;
    }

    public float width(int mip) { return Math.max(1, width >> mip); }
    public float height(int mip) { return Math.max(1, height >> mip); }
    public int mipLevels() { return mips; }

    public Frame publish() {
        var captured = streaming;
        streaming = null;
        try {
            return new Frame(tile, runtime == null ? null : runtime.acquire(), captured);
        } catch (RuntimeException | Error failure) {
            if (captured != null) captured.close();
            throw failure;
        }
    }

    @Override public void close() {
        if (streaming != null) {
            streaming.close();
            streaming = null;
        }
        runtime = null;
        width = height = mips = 0;
    }
}
