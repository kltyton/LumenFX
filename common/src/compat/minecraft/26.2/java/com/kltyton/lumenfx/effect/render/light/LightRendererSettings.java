package com.kltyton.lumenfx.effect.render.light;

import net.minecraft.world.phys.Vec3;

public record LightRendererSettings(boolean inverseSquaredFalloff, boolean affectsTranslucency,
                                    boolean allowMegaLights, boolean megaLightsCastShadows, boolean alphaScalesBrightness,
                                    float radiusScale, float defaultExponent, float specularScale, float diffuseScale,
                                    Vec3 colorAdd, boolean overrideInverseExposureBlend, float inverseExposureBlend,
                                    float defaultInverseExposureBlend) {
    public static final LightRendererSettings DEFAULT = new LightRendererSettings(true, false, false, false, false,
            1, 1, 1, 1, Vec3.ZERO, false, 0, 0);

    public LightRendererSettings(boolean inverseSquaredFalloff, boolean affectsTranslucency, boolean allowMegaLights,
                                  boolean megaLightsCastShadows, boolean alphaScalesBrightness, float radiusScale,
                                  float defaultExponent, float specularScale, float diffuseScale, Vec3 colorAdd,
                                  boolean overrideInverseExposureBlend, float inverseExposureBlend) {
        this(inverseSquaredFalloff, affectsTranslucency, allowMegaLights, megaLightsCastShadows, alphaScalesBrightness,
                radiusScale, defaultExponent, specularScale, diffuseScale, colorAdd, overrideInverseExposureBlend, inverseExposureBlend, 0);
    }

    public LightRendererSettings {
        if (colorAdd == null || !Double.isFinite(colorAdd.x) || !Double.isFinite(colorAdd.y) || !Double.isFinite(colorAdd.z)
                || !Float.isFinite(radiusScale) || !Float.isFinite(defaultExponent) || !Float.isFinite(specularScale)
                || !Float.isFinite(diffuseScale) || !Float.isFinite(inverseExposureBlend) || !Float.isFinite(defaultInverseExposureBlend))
            throw new IllegalArgumentException("Invalid Niagara light renderer settings");
    }

    public float exposureBlend(float projectDefault) { return overrideInverseExposureBlend ? inverseExposureBlend : projectDefault; }
    public float exposureBlend() { return exposureBlend(defaultInverseExposureBlend); }
}
