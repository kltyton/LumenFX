#ifdef LFX_REFLECTION_ENERGY
#moj_import <lumenfx:lighting/reflection_energy.glsl>
#endif
#ifdef LFX_SUBSTRATE_TREE_FUZZ
#moj_import <lumenfx:lighting/default_lit_brdf.glsl>
#moj_import <lumenfx:substrate/sheen_ltc.glsl>
#endif

float lfx_slab_layer_coverage(float top, float base) {
    return top + (1.0 - top) * base;
}

vec3 lfx_slab_layer_transmission(float top, float base, vec3 topT, vec3 baseT) {
    return top * (1.0 - base) * topT + (1.0 - top) * base * baseT + top * base * topT * baseT;
}

vec3 lfx_slab_layer_above(float top, float base, vec3 topT, vec3 baseT) {
    float coverage = lfx_slab_layer_coverage(top, base);
    return clamp(lfx_slab_layer_transmission(top, base, topT, baseT) / max(1e-10, coverage), 0.0, 1.0);
}

float lfx_slab_layer_volume_blend(vec3 mfp) {
    float transition = clamp(3.0 * (max(mfp.x, max(mfp.y, mfp.z)) - 0.04), 0.0, 1.0);
    return transition * transition;
}

vec3 lfx_slab_layer_extinction(vec3 mfp) {
    return max(vec3(0.000001), 1.0 / max(mfp, vec3(1e-12)));
}

vec3 lfx_slab_layer_normal_transmission(vec3 mfp) {
    return lfx_slab_layer_volume_blend(mfp) * exp(-lfx_slab_layer_extinction(mfp));
}

vec3 lfx_slab_layer_view_transmission(vec3 mfp, vec3 f0, vec3 f90, float roughness,
                                     float noV, float fuzzAmount, float fuzzRoughness) {
    f0 = clamp(f0, 0.0, 1.0);
    f90 = clamp(f90, 0.0, 1.0);
    noV = clamp(noV, -1.0, 1.0);
    float eta = 1.0 / (2.0 / (1.0 - sqrt(clamp(f0.g, 0.0, 0.99))) - 1.0);
    vec3 refracted = refract(vec3(sqrt(max(0.0, 1.0 - noV * noV)), 0.0, noV), vec3(0.0, 0.0, -1.0), eta);
    float path = 1.0 / max(0.0001, abs(refracted.z));
    float transmission = 1.0;
#ifdef LFX_REFLECTION_ENERGY
    float microOcclusion = clamp(50.0 * max(f0.r, max(f0.g, f0.b)), 0.0, 1.0);
    transmission = lfx_ggx_reflection_energy(clamp(roughness, 0.02, 1.0), max(1e-10, abs(noV)), f0, f90 * microOcclusion,
            LFX_SUBSTRATE_CONSERVATION != 0, LFX_SUBSTRATE_PRESERVATION != 0).diffuseTransmission;
#endif
#ifdef LFX_SUBSTRATE_TREE_FUZZ
    if (fuzzAmount > 0.0) {
        float sheenAlbedo = lfx_substrate_sheen_ltc_coefficients(noV, fuzzRoughness).z;
        transmission *= 1.0 - clamp(fuzzAmount, 0.0, 1.0) * sheenAlbedo;
    }
#endif
    return (transmission * lfx_slab_layer_volume_blend(mfp)) * exp(-lfx_slab_layer_extinction(mfp) * path);
}
