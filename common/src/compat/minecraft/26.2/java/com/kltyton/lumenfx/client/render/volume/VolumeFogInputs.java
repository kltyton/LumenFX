package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.client.render.fog.FogView;
import com.kltyton.lumenfx.client.render.fog.HeightFogUniforms;
import com.kltyton.lumenfx.client.render.fog.VolumetricFogTarget.Frame;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Fog;
import com.kltyton.lumenfx.effect.render.fog.ExponentialHeightFog;
import com.kltyton.lumenfx.effect.render.fog.FogSettings;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.volume.VolumeTracingSettings;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import org.jspecify.annotations.Nullable;

record VolumeFogInputs(@Nullable GpuBufferSlice height, @Nullable Frame froxel) {
    static final VolumeFogInputs NONE = new VolumeFogInputs(null, null);

    static VolumeFogInputs prepare(CommandEncoder encoder, FogSettings settings, FogView view, WorldHdrCapture scene,
                                   VolumeTracingSettings tracing) {
        if (!tracing.fogInscattering()) return NONE;
        if (settings instanceof VolumetricFog fog) {
            if (tracing.volumetricFog()) {
                var frame = scene.prepareSceneFog(fog);
                return new VolumeFogInputs(frame.heightUniforms(), frame);
            }
            settings = fog.height();
        }
        if (tracing.heightFog() && settings instanceof ExponentialHeightFog fog)
            return new VolumeFogInputs(HeightFogUniforms.write(encoder, fog, view), null);
        return NONE;
    }

    Fog mode() {
        return froxel != null ? Fog.FROXEL : height != null ? Fog.HEIGHT : Fog.NONE;
    }
}
