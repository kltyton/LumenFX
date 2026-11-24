package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.effect.render.fog.ExponentialHeightFog;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;

/** Packs the source height-fog view constants into a frame-owned uniform slice. */
public final class HeightFogUniforms {
    public static final int BYTES = 7 * 16;

    private HeightFogUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, ExponentialHeightFog fog, FogView view) {
        float cameraHeightCm = (float) view.cameraHeightCm();
        float maximumObserver = Float.MAX_VALUE;
        if (fog.primary().sceneDensity() > 0) maximumObserver = Math.min(maximumObserver, fog.primary().heightCm() + 65536.0f);
        if (fog.secondary().sceneDensity() > 0) maximumObserver = Math.min(maximumObserver, fog.secondary().heightCm() + 65536.0f);
        float observer = Math.min(cameraHeightCm, maximumObserver);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var allocation = encoder.transientMemory().allocateGpuMapped(BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = allocation.data().order(ByteOrder.nativeOrder());
            var primary = fog.primary();
            var secondary = fog.secondary();
            data.putFloat(collapsed(primary, observer)).putFloat(primary.sceneFalloff()).putFloat(maximumObserver).putFloat(fog.startCm());
            data.putFloat(collapsed(secondary, observer)).putFloat(secondary.sceneFalloff()).putFloat(secondary.sceneDensity()).putFloat(secondary.heightCm());
            data.putFloat(primary.sceneDensity()).putFloat(primary.heightCm()).putFloat(fog.cutoffCm()).putFloat(fog.endCm());
            data.putFloat((float)fog.inscattering().x).putFloat((float)fog.inscattering().y).putFloat((float)fog.inscattering().z).putFloat(1 - fog.maximumOpacity());
            var light = fog.directional();
            data.putFloat((float)light.color().x).putFloat((float)light.color().y).putFloat((float)light.color().z)
                    .putFloat(Math.clamp(light.exponent(), .000001f, 1000f));
            data.putFloat((float)light.direction().x).putFloat((float)light.direction().y).putFloat((float)light.direction().z).putFloat(light.startCm());
            data.putFloat(cameraHeightCm).putFloat(0).putFloat(0).putFloat(0);
            return allocation.slice();
        }
    }

    private static float collapsed(ExponentialHeightFog.Layer layer, float observer) {
        float power = Math.clamp(-layer.sceneFalloff() * (observer - layer.heightCm()), -125, 126);
        return layer.sceneDensity() * (float)Math.pow(2.0f, power);
    }
}
