#ifdef LFX_FORWARD_ATMOSPHERE_TRANSMITTANCE
uniform sampler2D LfxForwardAtmosphereTransmittance;
layout(std140) uniform LfxForwardAtmosphereParameters {
    vec4 LfxForwardAtmospherePlanetCenterCm;
    vec4 LfxForwardAtmosphereRadiiKm;
    vec4 LfxForwardAtmosphereLightDirection;
};

#moj_import <lumenfx:atmosphere/transmittance_math.glsl>

vec3 lfx_forward_atmosphere_transmittance(vec3 translatedWorldPositionCm) {
    vec3 planetCenterToWorldPosition = (translatedWorldPositionCm - LfxForwardAtmospherePlanetCenterCm.xyz) * 0.00001;
    return lfx_atmosphere_transmittance_along_ray(LfxForwardAtmosphereTransmittance, planetCenterToWorldPosition,
            LfxForwardAtmosphereLightDirection.xyz, LfxForwardAtmosphereRadiiKm.x, LfxForwardAtmosphereRadiiKm.y);
}
#else
vec3 lfx_forward_atmosphere_transmittance(vec3 translatedWorldPositionCm) {
    return vec3(1.0);
}
#endif
