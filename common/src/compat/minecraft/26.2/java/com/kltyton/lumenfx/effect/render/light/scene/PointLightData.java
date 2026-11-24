package com.kltyton.lumenfx.effect.render.light.scene;

import org.joml.Matrix4d;

/** Immutable point-component values published after its game-thread property updates. */
public interface PointLightData {
    PointLightShaderParameters shader();
    float radius();
    float volumetricScattering();
    float indirectLighting();
    boolean castDynamicShadow();
    boolean castStaticShadow();
    default boolean castVolumetricShadow() { return false; }
    default boolean transmitsThroughSurfaces() { return false; }
    boolean allowMegaLights();
    LightFunctionSettings lightFunction();
    PointShadowLightSettings shadowSettings();
    PointLightDistanceSettings distanceSettings();
    int lightingChannels();
    ContactShadowSettings contactShadows();
    Matrix4d worldToLight(Matrix4d destination);
}
