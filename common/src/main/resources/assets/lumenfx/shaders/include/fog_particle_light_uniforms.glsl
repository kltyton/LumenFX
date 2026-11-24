#if defined(LFX_FOG_DIRECTIONAL_LIGHT)
layout(std140) uniform FogDirectionalLight {
    vec4 FogDirectionalDirection;
    vec4 FogDirectionalColor;
    mat4 FogDirectionalFunctionMatrix;
};
#elif defined(LFX_FOG_INDIRECT_LIGHT)
layout(std140) uniform FogSkyLight {
    vec4 FogSkyIrradiance[3];
    vec4 FogSkyColorAndScattering;
    vec4 FogSkyHeightColorAndOverride;
    ivec4 FogSkyFlags;
};
#elif defined(LFX_FOG_LOCAL_LIGHT)
layout(std140) uniform FogLocalLight {
    vec4 FogLocalCentre;
    vec4 FogLocalColor;
    vec4 FogLocalDirection;
    vec4 FogLocalTangent;
    vec4 FogLocalShape;
    vec4 FogLocalRect;
    vec4 FogLocalRectUv;
    vec4 FogLocalCone;
};
#else
uniform samplerBuffer ParticleLights;
#endif
layout(std140) uniform FogParticleLights {
    mat4 FogLightWorldToClip;
    ivec4 FogParticleLightCount;
    vec4 FogParticleLightExposure;
    vec4 FogLightJitterOffsets[16];
};
