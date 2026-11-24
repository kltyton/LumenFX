#ifdef LFX_LEGACY_CLOTH_ENERGY
uniform sampler2D LfxLegacyClothEnergy;
#endif

vec3 lfx_cloth_lighting(vec4 custom, vec4 f0Roughness, vec3 normal, vec3 view, vec3 direction, float noL,
                       float sphere, float softSphere, float lineCos,
#ifdef LFX_RECT_LIGHT
                       LfxRectLight rect, mat3 viewToSource, float falloff, vec3 sourceRadiance,
#endif
                       out float diffuseWeight) {
    float noV = dot(normal, view);
    vec2 halfVector = lfx_default_lit_half_vector(noV, dot(normal, direction), dot(view, direction), sphere, true);
    noV = clamp(abs(noV) + 1e-5, 0.0, 1.0);
    float roughness = f0Roughness.w;
#ifdef LFX_RECT_LIGHT
    vec3 baseSpecular = lfx_rect_legacy_ggx(rect, normalize(viewToSource * normal), normalize(viewToSource * view),
            roughness, f0Roughness.rgb);
#else
    vec3 baseSpecular = lfx_default_lit_ggx(roughness, noV, noL, halfVector, sphere, softSphere, lineCos)
            * lfx_default_lit_fresnel(f0Roughness.rgb, halfVector.y);
#endif
    float alpha = roughness * roughness;
    float alphaSquared = alpha * alpha;
    float slope = (1.0 - alphaSquared) * halfVector.x * halfVector.x + alphaSquared;
    float distribution = (1.0 + 4.0 * alphaSquared * alphaSquared / max(slope * slope, 1e-20))
            / (LFX_BRDF_PI * (1.0 + 4.0 * alphaSquared));
    float visibility = 1.0 / (4.0 * (noL + noV - noL * noV));
    vec3 fuzz = lfx_default_lit_fresnel(custom.rgb * custom.rgb, halfVector.y) * distribution * visibility;
#ifdef LFX_RECT_LIGHT
    fuzz *= sourceRadiance * (falloff * noL);
#endif
    LfxReflectionEnergy baseEnergy = lfx_legacy_reflection_energy(roughness, noV, f0Roughness.rgb);
    float fuzzTransmission = 1.0;
#ifdef LFX_LEGACY_CLOTH_ENERGY
    fuzzTransmission -= textureLod(LfxLegacyClothEnergy, vec2(noV, roughness), 0.0).r;
#endif
    diffuseWeight = mix(baseEnergy.diffuseTransmission, fuzzTransmission, custom.a);
    return mix(baseSpecular * baseEnergy.multiScatter, fuzz, custom.a);
}
