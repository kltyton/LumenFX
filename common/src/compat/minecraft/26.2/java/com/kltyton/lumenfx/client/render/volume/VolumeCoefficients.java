package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.Address;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata.FilterMode;
import com.mojang.blaze3d.GpuFormat;

/** The three independent fields written by the canonical material evaluation pass. */
record VolumeCoefficients(ComputeVolume extinction, ComputeVolume emission, ComputeVolume albedo) implements AutoCloseable {
    private static final ComputeVolume.Sampling SAMPLING = new ComputeVolume.Sampling(
            new Settings(Address.CLAMP, Address.CLAMP, FilterMode.LINEAR, FilterMode.POINT, 1), Address.CLAMP);

    static VolumeCoefficients create(VolumePreshadingGrid grid, GpuFormat format) {
        var extinction = field("Extinction", grid, format);
        ComputeVolume emission = null;
        try {
            emission = field("Emission", grid, format);
            return new VolumeCoefficients(extinction, emission, field("Albedo", grid, format));
        } catch (RuntimeException | Error failure) {
            try { if (emission != null) emission.close(); }
            finally { extinction.close(); }
            throw failure;
        }
    }

    private static ComputeVolume field(String name, VolumePreshadingGrid grid, GpuFormat format) {
        return ComputeVolume.createTarget("Niagara.Volume." + name, grid.x(), grid.y(), grid.z(), format, true, false, new float[4], SAMPLING);
    }

    boolean matches(VolumePreshadingGrid grid, GpuFormat format) {
        return extinction.format() == format && extinction.width() == grid.x() && extinction.height() == grid.y() && extinction.depth() == grid.z();
    }

    @Override public void close() {
        try (extinction; emission; albedo) {
        }
    }
}
