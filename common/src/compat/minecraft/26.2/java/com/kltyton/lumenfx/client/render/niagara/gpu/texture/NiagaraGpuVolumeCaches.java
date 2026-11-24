package com.kltyton.lumenfx.client.render.niagara.gpu.texture;

import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureStorage;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.texture.NiagaraVolumeCache.Snapshot;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset.Frame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.GpuFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Render-thread volume textures shared by all stages and emitters using the same physical DI. */
public final class NiagaraGpuVolumeCaches implements AutoCloseable {
    private static final ComputeVolume.Sampling SAMPLING = new ComputeVolume.Sampling(
            new EffectSamplerFactory.Settings(Address.CLAMP, Address.CLAMP, FilterMode.LINEAR, FilterMode.POINT, 1), Address.CLAMP);
    private static final NativeTextureStorage STORAGE = new NativeTextureStorage(TextureStorageFormat.RGBA16F, false);

    private record Image(ComputeVolume texture, @Nullable Frame frame) { }
    private final Map<String, Image> images = new HashMap<>();

    void preStage(String id, Snapshot snapshot) {
        var asset = snapshot.asset();
        if (!snapshot.readFile() || asset == null) return;
        String key = SourceNames.canonical(id);
        var previous = images.get(key);
        var frame = snapshot.frame();
        if (previous != null && (frame == null || previous.frame() == frame)) return;
        int limit = ComputeVolume.maximumSize();
        if (Math.max(asset.width(), Math.max(asset.height(), asset.depth())) > limit)
            throw new IllegalArgumentException("Niagara Volume Cache exceeds 3D texture limits: " + id);

        ComputeVolume texture;
        String label = "Niagara.VolumeCache." + id;
        if (frame == null) {
            texture = ComputeVolume.createTarget(label, asset.width(), asset.height(), asset.depth(),
                    GpuFormat.RGBA16_FLOAT, false, false, new float[4], SAMPLING);
        } else {
            var pixels = MemoryUtil.memAlloc(asset.frameBytes());
            try {
                frame.unpack(pixels, asset.frameBytes());
                pixels.flip();
                texture = ComputeVolume.createSampled(label, asset.width(), asset.height(), asset.depth(), List.of(pixels), STORAGE, SAMPLING);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        }
        images.put(key, new Image(texture, frame));
        if (previous != null) previous.texture().close();
    }

    @Nullable ComputeVolume texture(String id) {
        var image = images.get(SourceNames.canonical(id));
        return image == null ? null : image.texture();
    }

    @Override public void close() {
        images.values().forEach(image -> image.texture().close());
        images.clear();
    }
}
