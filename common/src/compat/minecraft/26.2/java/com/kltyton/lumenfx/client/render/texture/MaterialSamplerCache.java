package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.kltyton.lumenfx.effect.render.material.MaterialSamplerSource;
import com.kltyton.lumenfx.effect.render.material.MaterialTextureSampler;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.HashMap;
import java.util.Map;

/** Shared World, Terrain Weightmap, and explicit per-material samplers for the current render resources. */
final class MaterialSamplerCache {
    // The fixed default desktop device profile uses anisotropic min/mag and point mip filtering for shared world groups.
    private static final int MAX_ANISOTROPY = 8;
    private static final int UNCLAMPED_MIP_COUNT = Integer.SIZE;
    private record Key(EffectSamplerFactory.Settings settings, float lodBias) { }
    private final Map<Key, GpuSampler> samplers = new HashMap<>();

    GpuSampler sampler(MaterialSamplerSource source, float viewMipBias) {
        Address address = source.clamps() ? Address.CLAMP : Address.WRAP;
        float bias = source.usesViewBias() && viewMipBias != 0 ? viewMipBias : 0;
        var settings = new EffectSamplerFactory.Settings(address, address, FilterMode.ANISO, FilterMode.POINT, MAX_ANISOTROPY);
        return sampler(settings, bias);
    }

    GpuSampler sampler(MaterialTextureSampler source, float viewMipBias) {
        var settings = new EffectSamplerFactory.Settings(
                address(source.addressX()), address(source.addressY()),
                filter(source.minMag()), filter(source.mip()), source.maxAnisotropy());
        return sampler(settings, viewMipBias);
    }

    private GpuSampler sampler(EffectSamplerFactory.Settings settings, float lodBias) {
        return samplers.computeIfAbsent(new Key(settings, lodBias), key -> EffectSamplerFactory.create(
                key.settings(), Address.WRAP, UNCLAMPED_MIP_COUNT, key.lodBias()));
    }

    private static Address address(MaterialTextureSampler.Address value) {
        return switch (value) {
            case WRAP -> Address.WRAP;
            case CLAMP -> Address.CLAMP;
            case MIRROR -> Address.MIRROR;
            case MIRROR_ONCE -> Address.MIRROR_ONCE;
        };
    }

    private static FilterMode filter(MaterialTextureSampler.Filter value) {
        return switch (value) {
            case POINT -> FilterMode.POINT;
            case LINEAR -> FilterMode.LINEAR;
            case ANISO -> FilterMode.ANISO;
        };
    }

    void clear() {
        samplers.values().forEach(GpuSampler::close);
        samplers.clear();
    }
}
