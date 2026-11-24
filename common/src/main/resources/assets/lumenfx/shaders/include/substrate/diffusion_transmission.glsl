#moj_import <lumenfx:lighting/subsurface_profile.glsl>

vec3 lfx_slab_diffusion_transmission(LfxAdaptiveSlab slab, vec3 albedo, float opticalMask, vec3 view, vec3 light) {
    float thickness = (1.0 - opticalMask) * 5.0;
    if (slab.diffusionProfile) {
        if (slab.thin) thickness = lfx_adaptive_profile_thickness(slab.diffusionData);
        return lfx_subsurface_profile_transmission(lfx_adaptive_profile_id(slab.diffusionData), thickness,
                slab.baseNormal, view, light);
    }

    vec3 mfp = max(lfx_adaptive_unpack_unsigned(slab.diffusionData), vec3(1e-8));
    vec3 offset = abs(albedo - 0.8);
    vec3 perpendicular = 1.85 - albedo + 7.0 * offset * offset * offset;
    if (slab.thin) thickness = 100.0;
    vec3 attenuation = -perpendicular * (thickness / (100.0 * mfp));
    vec3 transmittance = 0.25 * albedo * (exp(attenuation) + 3.0 * exp(attenuation / 3.0));
    float reciprocalIor = 1.0 / (2.0 / (1.0 - sqrt(clamp(slab.specular.g, 0.0, 0.99))) - 1.0);
    vec3 refracted = refract(view, -slab.baseNormal, reciprocalIor);
    float g = clamp(slab.phase, -0.999, 0.999);
    float denominator = max(1e-6, 1.0 + g * g + 2.0 * g * dot(light, refracted));
    float phase = (1.0 - g * g) / (4.0 * LFX_BRDF_PI * denominator * sqrt(denominator));
    return transmittance * phase;
}
