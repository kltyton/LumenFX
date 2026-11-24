#moj_import <lumenfx:lighting/clear_coat_transmittance.glsl>

struct LfxClearCoatLighting {
    vec3 diffuseWeight;
    vec3 specular;
};

LfxClearCoatLighting lfx_evaluate_clear_coat(vec4 custom, vec3 base, float metallic, vec4 f0Roughness,
                                            vec3 normal, vec3 topNormal, vec3 view, vec3 direction, float noL,
                                            float sphere, float softSphere, float lineCos, bool anisotropic,
#ifdef LFX_RECT_LIGHT
                                            bool useRect, LfxRectLight rect, mat3 viewToSource, float falloff, vec3 sourceRadiance,
#ifdef LFX_RECT_LIGHT_ATLAS
                                            LfxRectTexture rectTexture,
#endif
#endif
                                            vec4 tangentAnisotropy) {
    float roughness = f0Roughness.w;
    float coatRoughness = max(custom.y, 0.02);
    float roughnessCompensation = 1.0 - roughness * roughness;
    float coatSphere = roughnessCompensation > 0.0
            ? clamp(sphere * (1.0 - coatRoughness * coatRoughness) / roughnessCompensation, 0.0, 1.0) : 0.0;
    float voL = dot(view, direction);
    float topNoV = dot(topNormal, view), topNoL = dot(topNormal, direction);
#ifdef LFX_CLEAR_COAT_BOTTOM_NORMAL
    vec2 topHalf = lfx_default_lit_half_vector(topNoV, topNoL, voL, coatSphere, false);
#else
    vec2 topHalf = lfx_default_lit_half_vector(topNoV, topNoL, voL, coatSphere, true);
#endif
    topNoV = clamp(abs(topNoV) + 1e-5, 0.0, 1.0);
    LfxReflectionEnergy coatingEnergy = lfx_legacy_reflection_energy(coatRoughness, topNoV, vec3(0.04));
    float coatingVoH = topHalf.y;
#ifdef LFX_RECT_LIGHT
    if (useRect) coatingVoH = topNoV;
#endif
    float coatingFresnel = lfx_default_lit_fresnel(vec3(0.04), coatingVoH).r;
    vec3 topSpecular;
#ifdef LFX_RECT_LIGHT
    vec3 sourceView = normalize(viewToSource * view);
    if (useRect) {
        topSpecular = lfx_rect_legacy_ggx(rect, normalize(viewToSource * topNormal), sourceView, coatRoughness, vec3(0.04)
#ifdef LFX_RECT_LIGHT_ATLAS
                , rectTexture
#endif
                ) * (custom.x * coatingEnergy.multiScatter);
    } else
#endif
    {
        topSpecular = vec3(lfx_default_lit_ggx(coatRoughness, topNoV, noL, topHalf, coatSphere, softSphere, lineCos)
                * coatingFresnel * custom.x * coatingEnergy.multiScatter.r);
    }
    float baseNoV = topNoV, baseNoL = topNoL;
    vec2 baseHalf = vec2(topHalf.x, coatingVoH);
#ifdef LFX_CLEAR_COAT_BOTTOM_NORMAL
    baseNoV = clamp(dot(normal, view), 0.0, 1.0);
    baseNoL = clamp(dot(normal, direction), 0.0, 1.0);
    if (sphere > 0.0) {
        baseHalf = lfx_default_lit_half_vector(baseNoV, baseNoL, clamp(voL, 0.0, 1.0), sphere, true);
    } else {
        vec3 halfVector = normalize(view + direction);
        baseHalf = clamp(vec2(dot(normal, halfVector), dot(view, halfVector)), 0.0, 1.0);
    }
    baseNoV = clamp(baseNoV + 1e-5, 0.0, 1.0);
#endif
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
    vec2 tangentH = vec2(0.0), tangentV = vec2(0.0), tangentL = vec2(0.0);
    if (anisotropic) {
        vec3 halfVector = normalize(view + direction);
        baseHalf = clamp(vec2(dot(topNormal, halfVector), dot(view, halfVector)), 0.0, 1.0);
        baseNoV = dot(topNormal, view);
        baseNoL = dot(topNormal, direction);
        vec3 tangent = tangentAnisotropy.xyz;
        vec3 bitangent = normalize(cross(normal, tangent));
        tangentH = vec2(dot(halfVector, tangent), dot(halfVector, bitangent));
        tangentV = vec2(dot(view, tangent), dot(view, bitangent));
        tangentL = vec2(dot(direction, tangent), dot(direction, bitangent));
    }
#endif
    vec3 transmitted = lfx_clear_coat_refract(baseNoV, baseNoL, baseHalf);
#ifdef LFX_RECT_LIGHT
    if (useRect) transmitted.z = transmitted.x;
#endif
    vec3 transmission = lfx_clear_coat_transmittance(transmitted.xy, metallic, base);
#if LFX_LEGACY_ENERGY
    float coatingTransmission = coatingEnergy.diffuseTransmission;
#else
    float coatingTransmission = 1.0 - coatingFresnel;
#endif
    transmission *= coatingTransmission * coatingTransmission;
    LfxReflectionEnergy baseEnergy = lfx_legacy_reflection_energy(roughness, transmitted.x, f0Roughness.rgb);
    vec3 diffuseWeight = mix(vec3(1.0), transmission, custom.x) * baseEnergy.diffuseTransmission;
#ifdef LFX_RECT_LIGHT
    if (useRect && !anisotropic) {
        vec3 baseSpecular = lfx_rect_legacy_ggx(rect, normalize(viewToSource * normal), sourceView, roughness, f0Roughness.rgb
#ifdef LFX_RECT_LIGHT_ATLAS
                , rectTexture
#endif
                );
        return LfxClearCoatLighting(diffuseWeight, topSpecular + baseSpecular * mix(vec3(1.0), transmission, custom.x));
    }
#endif
    float distributionVisibility;
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
    if (anisotropic) {
        distributionVisibility = lfx_default_lit_anisotropic_ggx(roughness, tangentAnisotropy.w, baseHalf.x, transmitted.x,
                transmitted.y, tangentH, tangentV, tangentL);
    } else
#endif
    {
        distributionVisibility = lfx_default_lit_ggx(roughness, transmitted.x, noL, baseHalf, sphere, softSphere, lineCos);
    }
    vec3 ordinary = lfx_default_lit_fresnel(f0Roughness.rgb, baseHalf.y);
    vec3 refracted = transmission * lfx_default_lit_fresnel(f0Roughness.rgb, transmitted.z);
    vec3 bottomSpecular = distributionVisibility * mix(ordinary, refracted, custom.x);
#ifdef LFX_RECT_LIGHT
    if (useRect) bottomSpecular *= sourceRadiance * (falloff * noL);
#endif
    return LfxClearCoatLighting(diffuseWeight, topSpecular + bottomSpecular);
}

#ifndef LFX_RECT_LIGHT_ATLAS
LfxClearCoatLighting lfx_clear_coat_lighting(vec4 custom, vec3 base, float metallic, vec4 f0Roughness,
                                            vec3 normal, vec3 topNormal, vec3 view, vec3 direction, float noL,
                                            float sphere, float softSphere, float lineCos, bool anisotropic,
#ifdef LFX_RECT_LIGHT
                                            LfxRectLight rect, mat3 viewToSource, float falloff, vec3 sourceRadiance,
#endif
                                            vec4 tangentAnisotropy) {
    return lfx_evaluate_clear_coat(custom, base, metallic, f0Roughness, normal, topNormal, view, direction,
            noL, sphere, softSphere, lineCos, anisotropic,
#ifdef LFX_RECT_LIGHT
            true, rect, viewToSource, falloff, sourceRadiance,
#endif
            tangentAnisotropy);
}
#endif

