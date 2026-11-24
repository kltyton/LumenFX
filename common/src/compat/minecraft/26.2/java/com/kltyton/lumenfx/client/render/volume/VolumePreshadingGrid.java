package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.effect.render.volume.VolumeTracingSettings;
import org.joml.Matrix4dc;
import org.joml.Matrix4fc;

record VolumePreshadingGrid(int x, int y, int z, float lodFactor) {
    static VolumePreshadingGrid resolve(NiagaraVolumeInstance volume, Matrix4dc relative, Matrix4fc projection, int width,
                                       double nearClip, VolumeTracingSettings tracing, VolumeLightingGrid lighting) {
        var settings = tracing.preshading();
        double lod = tracing.continuousLod() ? VolumeLightingGrid.lodFactor(volume, relative, projection, width, nearClip) : 1;
        if (settings.continuousLod() == 2)
            return new VolumePreshadingGrid(lighting.x(), lighting.y(), lighting.z(), (float)lod);
        double factor = settings.continuousLod() == 1 ? lod : 1;
        return new VolumePreshadingGrid(Math.max(1, (int)(settings.x(volume.resolutionX()) / factor)),
                Math.max(1, (int)(settings.y(volume.resolutionY()) / factor)),
                Math.max(1, (int)(settings.z(volume.resolutionZ()) / factor)), (float)lod);
    }
}
