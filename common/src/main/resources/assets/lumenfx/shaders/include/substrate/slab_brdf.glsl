#moj_import <lumenfx:lighting/clear_coat_transmittance.glsl>

LfxReflectionEnergy lfx_substrate_specular_energy(float roughness, float noV, vec3 f0, vec3 f90) {
    return lfx_ggx_reflection_energy(roughness, noV, f0, f90, LFX_SUBSTRATE_CONSERVATION != 0, LFX_SUBSTRATE_PRESERVATION != 0);
}

struct LfxSlabLightContext {
    vec2 halfVector;
    vec2 tangentV;
    vec2 tangentL;
    vec2 tangentH;
    float noV;
    float noL;
    float anisotropy;
    bool anisotropic;
};

vec3 lfx_substrate_reflect(float roughness, LfxSlabLightContext bxdf, vec3 f0, vec3 f90,
                          vec3 area, LfxReflectionEnergy energy) {
    float ggx;
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
    if (bxdf.anisotropic) {
        ggx = lfx_default_lit_anisotropic_ggx(roughness, bxdf.anisotropy, bxdf.halfVector.x, bxdf.noV, bxdf.noL,
                bxdf.tangentH, bxdf.tangentV, bxdf.tangentL);
    } else
#endif
    {
        ggx = lfx_ggx_area(roughness, bxdf.noV, bxdf.noL, bxdf.halfVector, area.x, area.y, area.z,
                true, LFX_SUBSTRATE_ACCURATE != 0);
    }
    float edge = 1.0 - bxdf.halfVector.y;
    float edgeSquared = edge * edge;
    vec3 fresnel = mix(f0, f90, edgeSquared * edgeSquared * edge);
    return fresnel * energy.multiScatter * ggx;
}

LfxSlabLightContext lfx_substrate_light_context(vec3 normal, vec3 view, vec3 direction, float noL,
        float sphereSinAlpha, vec4 tangentAnisotropy, bool anisotropic) {
    float rawNoV = dot(normal, view);
    float noV = clamp(max(abs(rawNoV), 1e-10), 0.0, 1.0);
    float voL = dot(view, direction);
    float rawNoL = dot(normal, direction);
    LfxSlabLightContext bxdf = LfxSlabLightContext(vec2(0.0), vec2(0.0), vec2(0.0), vec2(0.0), noV, noL,
            tangentAnisotropy.w, anisotropic);
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
    if (anisotropic) {
        bxdf.halfVector = lfx_default_lit_half_vector(rawNoV, rawNoL, voL, 0.0, false);
        vec3 tangent = tangentAnisotropy.xyz;
        vec3 bitangent = normalize(cross(normal, tangent));
        bxdf.tangentV = vec2(dot(tangent, view), dot(bitangent, view));
        bxdf.tangentL = vec2(dot(tangent, direction), dot(bitangent, direction));
        bxdf.tangentH = (bxdf.tangentV + bxdf.tangentL) * inversesqrt(max(1e-8, 2.0 + 2.0 * voL));
    } else
#endif
    {
        bxdf.halfVector = lfx_default_lit_half_vector(rawNoV, rawNoL, voL, sphereSinAlpha, true);
    }
    return bxdf;
}

vec3 lfx_substrate_slab_light(vec3 diffuse, vec4 f0Roughness, vec3 edgeColor, vec2 haze, bool simpleCoat,
        vec3 normal, vec3 topNormal, vec3 view, vec3 direction, float noL, float diffuseNoL, vec3 area, float retroreflection,
        vec4 tangentAnisotropy, bool anisotropic, vec2 scales, float specularProfile, vec3 volumeMfp, float volumePhase,
#ifdef LFX_RECT_LIGHT
        LfxRectLight rect, mat3 viewToSource,
#endif
        out vec3 specularResponse) {
    specularResponse = vec3(0.0);
    float roughness = max(f0Roughness.w, 0.02);
    vec3 f0 = f0Roughness.rgb;
    float peakF0 = max(f0.r, max(f0.g, f0.b));
    vec3 f90 = edgeColor * clamp(50.0 * peakF0, 0.0, 1.0);
    float rawNoV = dot(normal, view);
    float voL = dot(view, direction);
#ifdef LFX_RECT_LIGHT
    anisotropic = false;
#endif
    LfxSlabLightContext bxdf = lfx_substrate_light_context(normal, view, direction, noL, area.x, tangentAnisotropy, anisotropic);
    LfxReflectionEnergy primary = lfx_substrate_specular_energy(roughness, bxdf.noV, f0, f90);
    float hazeRoughness = max(haze.x, 0.02);
    LfxSlabLightContext secondaryBxdf = bxdf;
    vec3 secondaryF0 = f0, secondaryF90 = f90;
    vec3 coatThroughput = vec3(1.0);
    if (simpleCoat && haze.y > 0.0) {
        secondaryBxdf = lfx_substrate_light_context(topNormal, view, direction, noL, area.x, vec4(0.0), false);
        secondaryF0 = vec3(0.04);
        secondaryF90 = vec3(1.0);
        vec3 refraction = lfx_clear_coat_refract(secondaryBxdf.noV, dot(topNormal, direction), secondaryBxdf.halfVector);
        float metallic = clamp((peakF0 - 0.08) / (0.4 - 0.08), 0.0, 1.0);
        vec3 transmittance = lfx_clear_coat_transmittance(refraction.xy, metallic, mix(diffuse, f0, metallic));
        float fresnel = 0.04 + 0.96 * pow(1.0 - secondaryBxdf.halfVector.y, 5.0);
        coatThroughput = mix(vec3(1.0), transmittance * (1.0 - fresnel), haze.y);
    }
    LfxReflectionEnergy secondary = primary;
    if (haze.y > 0.0)
        secondary = lfx_substrate_specular_energy(hazeRoughness, secondaryBxdf.noV, secondaryF0, secondaryF90);
    float transmission = mix(primary.diffuseTransmission, secondary.diffuseTransmission, haze.y);
#if LFX_SUBSTRATE_ROUGH_DIFFUSE
    vec3 diffuseBrdf = lfx_diffuse_eon(diffuse, retroreflection * roughness * 0.4,
            clamp(rawNoV, 0.0, 1.0), diffuseNoL, clamp(voL, -1.0, 1.0));
#else
    vec3 diffuseBrdf = diffuse / LFX_BRDF_PI;
#endif
    vec3 response = diffuseBrdf * coatThroughput * (transmission * scales.x * diffuseNoL);
#if LFX_SUBSTRATE_VOLUME
    if (any(greaterThan(volumeMfp, vec3(0.0)))) {
        float phase = clamp(volumePhase, -0.999, 0.999);
        float denominator = max(1e-6, 1.0 + phase * phase + 2.0 * phase * voL);
        float scattering = (1.0 - phase * phase) / (denominator * sqrt(denominator));
        float lightNoL = clamp(dot(normal, direction), 0.0, 1.0);
        vec3 volume = lfx_simple_volume_punctual(LfxSlabPunctualVolume, volumeMfp, diffuse, bxdf.noV, lightNoL);
        response = mix(response, volume * (scattering * lightNoL * transmission * scales.x), lfx_slab_layer_volume_blend(volumeMfp));
    }
#endif
    if (scales.y == 0.0) return response;
#ifdef LFX_RECT_LIGHT
    vec3 sourceView = normalize(viewToSource * view);
    vec3 reflected = lfx_rect_ggx(rect, normalize(viewToSource * normal), sourceView, roughness, f0, f90)
            * primary.multiScatter;
    if (haze.y > 0.0) {
        vec3 hazeLobe = lfx_rect_ggx(rect, normalize(viewToSource * (simpleCoat ? topNormal : normal)),
                sourceView, hazeRoughness, secondaryF0, secondaryF90) * secondary.multiScatter;
        reflected = simpleCoat ? reflected * coatThroughput + hazeLobe * haze.y : mix(reflected, hazeLobe, haze.y);
    }
#else
    if (noL <= 0.0) return response;
    vec3 reflected = lfx_substrate_reflect(roughness, bxdf, f0, f90, area, primary);
    if (haze.y > 0.0) {
        vec3 hazeLobe = lfx_substrate_reflect(hazeRoughness, secondaryBxdf, secondaryF0, secondaryF90, area, secondary);
        reflected = simpleCoat ? reflected * coatThroughput + hazeLobe * haze.y : mix(reflected, hazeLobe, haze.y);
    }
#endif
#if LFX_SUBSTRATE_SPECULAR_PROFILE
    reflected *= lfx_specular_profile_light(specularProfile, bxdf.noV, clamp(dot(normal, direction), 0.0, 1.0), bxdf.halfVector);
#endif
#ifdef LFX_RECT_LIGHT
    specularResponse = reflected * scales.y;
#else
    specularResponse = reflected * (scales.y * noL);
#endif
    return response;
}
