package com.kltyton.lumenfx.effect.render.light;

import net.minecraft.world.phys.Vec3;

/** Source simple-light data; radius remains in centimeters and position is in host world coordinates. */
public record ParticleLight(long sourcePrimitive, int lightId, Vec3 position, float radius, Vec3 color,
                            float exponent, float inverseExposureBlend, boolean affectsTranslucency,
                            boolean allowMegaLights, boolean megaLightsCastShadows,
                            float volumetricScattering, float specularScale, float diffuseScale) { }
