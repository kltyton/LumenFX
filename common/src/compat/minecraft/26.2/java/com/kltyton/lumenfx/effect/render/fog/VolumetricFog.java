package com.kltyton.lumenfx.effect.render.fog;

import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Linear medium inputs for emissive or scene-lit volume integration. */
public record VolumetricFog(ExponentialHeightFog height, float startCm, float endCm, float nearFadeCm,
                            float extinctionScale, Vec3 emissive, boolean emissiveScalesWithDensity,
                            int pixelSize, int slices, float depthDistribution, boolean temporal,
                            float historyWeight, boolean conservativeDepth, float centerOffset,
                            float centerThreshold, int maximumVolumeDimension, Vec3 albedo,
                            float scatteringDistribution, float inverseSquaredBiasScale,
                            boolean sceneLighting, boolean jitter, int historyMissSamples,
                            float sampleJitterMultiplier, float lightSoftFading, boolean rectLightTexture,
                            boolean overrideLightColors, @Nullable SkyLightIrradiance skyLight,
                            @Nullable Precomputed precomputedLighting) implements FogSettings {
    public record Precomputed(VolumetricLightmap lightmap, float scatteringIntensity) {
        public Precomputed {
            Objects.requireNonNull(lightmap);
            if (!Float.isFinite(scatteringIntensity) || scatteringIntensity < 0)
                throw new IllegalArgumentException("Invalid static fog scattering intensity");
        }
    }

    public VolumetricFog(ExponentialHeightFog height, float startCm, float endCm, float nearFadeCm,
                         float extinctionScale, Vec3 emissive, boolean emissiveScalesWithDensity,
                         int pixelSize, int slices, float depthDistribution, boolean temporal,
                         float historyWeight, boolean conservativeDepth, float centerOffset,
                         float centerThreshold, int maximumVolumeDimension, Vec3 albedo,
                         float scatteringDistribution, float inverseSquaredBiasScale, boolean sceneLighting,
                         boolean jitter, int historyMissSamples, float sampleJitterMultiplier,
                         float lightSoftFading, boolean rectLightTexture, boolean overrideLightColors) {
        this(height, startCm, endCm, nearFadeCm, extinctionScale, emissive, emissiveScalesWithDensity,
                pixelSize, slices, depthDistribution, temporal, historyWeight, conservativeDepth, centerOffset, centerThreshold,
                maximumVolumeDimension, albedo, scatteringDistribution, inverseSquaredBiasScale, sceneLighting, jitter,
                historyMissSamples, sampleJitterMultiplier, lightSoftFading, rectLightTexture, overrideLightColors, null, null);
    }

    public VolumetricFog(ExponentialHeightFog height, float startCm, float endCm, float nearFadeCm,
                         float extinctionScale, Vec3 emissive, boolean emissiveScalesWithDensity,
                         int pixelSize, int slices, float depthDistribution, boolean temporal,
                         float historyWeight, boolean conservativeDepth, float centerOffset,
                         float centerThreshold, int maximumVolumeDimension, Vec3 albedo,
                         float scatteringDistribution, float inverseSquaredBiasScale, boolean sceneLighting,
                         boolean jitter, int historyMissSamples, float sampleJitterMultiplier,
                         float lightSoftFading, boolean rectLightTexture) {
        this(height, startCm, endCm, nearFadeCm, extinctionScale, emissive, emissiveScalesWithDensity,
                pixelSize, slices, depthDistribution, temporal, historyWeight, conservativeDepth, centerOffset, centerThreshold,
                maximumVolumeDimension, albedo, scatteringDistribution, inverseSquaredBiasScale, sceneLighting, jitter,
                historyMissSamples, sampleJitterMultiplier, lightSoftFading, rectLightTexture, false);
    }
    public VolumetricFog(ExponentialHeightFog height, float startCm, float endCm, float nearFadeCm,
                         float extinctionScale, Vec3 emissive, boolean emissiveScalesWithDensity,
                         int pixelSize, int slices, float depthDistribution, boolean temporal,
                         float historyWeight, boolean conservativeDepth, float centerOffset,
                         float centerThreshold, int maximumVolumeDimension, Vec3 albedo,
                         float scatteringDistribution, float inverseSquaredBiasScale,
                         boolean sceneLighting, boolean jitter, int historyMissSamples, float sampleJitterMultiplier) {
        this(height, startCm, endCm, nearFadeCm, extinctionScale, emissive, emissiveScalesWithDensity,
                pixelSize, slices, depthDistribution, temporal, historyWeight, conservativeDepth, centerOffset,
                centerThreshold, maximumVolumeDimension, albedo, scatteringDistribution, inverseSquaredBiasScale,
                sceneLighting, jitter, historyMissSamples, sampleJitterMultiplier, 0, false);
    }
    public VolumetricFog(ExponentialHeightFog height, float startCm, float endCm, float nearFadeCm,
                         float extinctionScale, Vec3 emissive, boolean emissiveScalesWithDensity,
                         int pixelSize, int slices, float depthDistribution, boolean temporal,
                         float historyWeight, boolean conservativeDepth, float centerOffset,
                         float centerThreshold, int maximumVolumeDimension, Vec3 albedo,
                         float scatteringDistribution, float inverseSquaredBiasScale, boolean sceneLighting) {
        this(height, startCm, endCm, nearFadeCm, extinctionScale, emissive, emissiveScalesWithDensity,
                pixelSize, slices, depthDistribution, temporal, historyWeight, conservativeDepth,
                centerOffset, centerThreshold, maximumVolumeDimension, albedo, scatteringDistribution,
                inverseSquaredBiasScale, sceneLighting, true, 4, 0);
    }

    public VolumetricFog(ExponentialHeightFog height, float startCm, float endCm, float nearFadeCm,
                         float extinctionScale, Vec3 emissive, boolean emissiveScalesWithDensity,
                         int pixelSize, int slices, float depthDistribution, boolean temporal,
                         float historyWeight, boolean conservativeDepth, float centerOffset,
                         float centerThreshold, int maximumVolumeDimension) {
        this(height, startCm, endCm, nearFadeCm, extinctionScale, emissive, emissiveScalesWithDensity,
                pixelSize, slices, depthDistribution, temporal, historyWeight, conservativeDepth,
                centerOffset, centerThreshold, maximumVolumeDimension, new Vec3(1, 1, 1), .2f, 1, false);
    }

    public VolumetricFog {
        if (height == null || emissive == null || !Float.isFinite(startCm) || !Float.isFinite(endCm)
                || endCm <= startCm || !Float.isFinite(nearFadeCm) || !Float.isFinite(extinctionScale)
                || !Float.isFinite((float)emissive.x) || !Float.isFinite((float)emissive.y) || !Float.isFinite((float)emissive.z)
                || pixelSize < 1 || slices < 1 || !Float.isFinite(depthDistribution) || depthDistribution <= 0
                || !Float.isFinite(historyWeight) || historyWeight < 0 || historyWeight > 1
                || !Float.isFinite(centerOffset) || !Float.isFinite(centerThreshold) || maximumVolumeDimension < 1
                || albedo == null || !Double.isFinite(albedo.x) || !Double.isFinite(albedo.y) || !Double.isFinite(albedo.z)
                || albedo.x < 0 || albedo.x > 1 || albedo.y < 0 || albedo.y > 1 || albedo.z < 0 || albedo.z > 1
                || !Float.isFinite(scatteringDistribution) || !Float.isFinite(inverseSquaredBiasScale)
                || !Float.isFinite(sampleJitterMultiplier) || !Float.isFinite(lightSoftFading))
            throw new IllegalArgumentException("Invalid source volume fog parameters");
        scatteringDistribution = Math.clamp(scatteringDistribution, -.99f, .99f);
        lightSoftFading = Math.max(0, lightSoftFading);
        historyMissSamples = historyMissSamples <= 1 ? 1 : historyMissSamples <= 4 ? 4 : historyMissSamples <= 8 ? 8 : 16;
    }
}
