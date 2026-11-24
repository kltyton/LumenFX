package com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset.Frame;
import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Per-instance CPU state and the frame published to Niagara's render stages. */
public final class NiagaraVolumeCache {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Snapshot(@Nullable NiagaraVolumeCacheAsset asset, @Nullable Frame frame, boolean readFile) { }

    private final @Nullable Identifier source;
    private final @Nullable NiagaraVolumeCacheAsset asset;
    private int frame;
    private boolean readFile;

    public NiagaraVolumeCache(JsonObject definition, Map<Identifier, NiagaraVolumeCacheAsset> assets) {
        var id = definition.get("asset");
        source = id == null || id.isJsonNull() ? null : Identifier.parse(id.getAsString());
        asset = source == null ? null : Objects.requireNonNull(assets.get(source), "Unresolved Niagara Volume Cache " + source);
    }

    public synchronized void frame(int value) {
        frame = value;
        if (asset == null || asset.frame(value) == null)
            LOGGER.warn("Niagara Volume Cache could not read frame {} from {}", value, source);
    }

    public synchronized void readFile(boolean value) { readFile = value; }
    public int width() { return asset == null ? 0 : asset.width(); }
    public int height() { return asset == null ? 0 : asset.height(); }
    public int depth() { return asset == null ? 0 : asset.depth(); }

    public synchronized Snapshot snapshot() { return new Snapshot(asset, asset == null ? null : asset.frame(frame), readFile); }
}
