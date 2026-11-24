package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.effect.render.volume.VolumeTracingSettings;
import org.joml.Matrix4dc;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

/** Source continuous LOD and per-axis limits for the volume's in-scattering cache. */
record VolumeLightingGrid(int x, int y, int z) {
    static VolumeLightingGrid resolve(NiagaraVolumeInstance volume, Matrix4dc cameraRelative,
                                      Matrix4fc projection, int viewportWidth, double nearClip, VolumeTracingSettings tracing) {
        double downsample = tracing.lightingDownsample() > 0 ? tracing.lightingDownsample()
                : volume.settings().lightingDownsampleFactor() * (tracing.continuousLod()
                        ? lodFactor(volume, cameraRelative, projection, viewportWidth, nearClip) : 1);
        downsample = Math.max(downsample, .125);
        var resolution = tracing.preshading();
        return new VolumeLightingGrid(axis(resolution.x(volume.resolutionX()), downsample, 1024),
                axis(resolution.y(volume.resolutionY()), downsample, 1024), axis(resolution.z(volume.resolutionZ()), downsample, 512));
    }

    static double lodFactor(NiagaraVolumeInstance volume, Matrix4dc cameraRelative,
                                    Matrix4fc projection, int viewportWidth, double nearClip) {
        var bounds = volume.localBounds();
        double x = (bounds.minX() + bounds.maxX()) * .5;
        double y = (bounds.minY() + bounds.maxY()) * .5;
        double z = (bounds.minZ() + bounds.maxZ()) * .5;
        double hx = bounds.maxX() - x, hy = bounds.maxY() - y, hz = bounds.maxZ() - z;
        var center = cameraRelative.transformPosition(new Vector3d(x, y, z));
        double rx = Math.abs(cameraRelative.m00()) * hx + Math.abs(cameraRelative.m10()) * hy + Math.abs(cameraRelative.m20()) * hz;
        double ry = Math.abs(cameraRelative.m01()) * hx + Math.abs(cameraRelative.m11()) * hy + Math.abs(cameraRelative.m21()) * hz;
        double rz = Math.abs(cameraRelative.m02()) * hx + Math.abs(cameraRelative.m12()) * hy + Math.abs(cameraRelative.m22()) * hz;
        double radius = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double distance = Math.max(center.length() - radius, nearClip);
        double pixelSize = distance * 2 / (Math.abs(projection.m00()) * viewportWidth);
        double coverage = 2 * Math.max(rx, Math.max(ry, rz)) / pixelSize;
        int minimumVoxels = Math.min(volume.resolutionX(), Math.min(volume.resolutionY(), volume.resolutionZ()));
        double maximumLodFactor = Integer.highestOneBit(minimumVoxels);
        return Math.clamp(minimumVoxels / coverage, 1, maximumLodFactor);
    }

    private static int axis(int resolution, double factor, int max) {
        return Math.clamp((int)(resolution / factor), 1, max);
    }
}
