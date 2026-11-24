#ifdef LFX_REFLECTION_ENERGY
uniform sampler2D LfxGgxReflectionEnergy;
#endif

struct LfxReflectionEnergy {
    vec3 multiScatter;
    float diffuseTransmission;
    vec3 directionalAlbedo;
};

LfxReflectionEnergy lfx_ggx_reflection_energy(float roughness, float noV, vec3 f0, vec3 f90, bool conserve, bool preserve,
                                               bool evaluateAlbedo) {
    LfxReflectionEnergy result = LfxReflectionEnergy(vec3(1.0), 1.0, f0);
#ifdef LFX_REFLECTION_ENERGY
    if (conserve || preserve || evaluateAlbedo) {
        vec2 energy = textureLod(LfxGgxReflectionEnergy, vec2(noV, roughness), 0.0).rg;
        if (conserve) result.multiScatter += f0 * ((1.0 - energy.x) / energy.x);
        result.directionalAlbedo = result.multiScatter * (energy.x * f0 + energy.y * (f90 - f0));
        if (preserve) {
            const vec3 luminance = vec3(0.2126390059, 0.7151686788, 0.0721923154);
            result.diffuseTransmission -= dot(result.directionalAlbedo, luminance);
        }
    }
#endif
    return result;
}

LfxReflectionEnergy lfx_ggx_reflection_energy(float roughness, float noV, vec3 f0, vec3 f90, bool conserve, bool preserve) {
    return lfx_ggx_reflection_energy(roughness, noV, f0, f90, conserve, preserve, false);
}

#ifdef LFX_LEGACY_ENERGY_CONSERVE
LfxReflectionEnergy lfx_legacy_reflection_energy(float roughness, float noV, vec3 f0) {
    vec3 f90 = vec3(clamp(50.0 * max(f0.r, max(f0.g, f0.b)), 0.0, 1.0));
    return lfx_ggx_reflection_energy(roughness, noV, f0, f90, LFX_LEGACY_ENERGY_CONSERVE != 0,
            LFX_LEGACY_ENERGY_PRESERVE != 0);
}
#endif

