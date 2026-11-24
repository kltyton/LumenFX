float lfx_slab_parameter_weight(float fromCoverage, float toCoverage, float fromMix, float toMix) {
    float fromWeight = clamp(fromCoverage, 0.0, 1.0) * fromMix;
    float toWeight = clamp(toCoverage, 0.0, 1.0) * toMix;
    return toWeight / max(1e-10, fromWeight + toWeight);
}

vec3 lfx_slab_bake_f90(vec3 f0, vec3 f90, float roughness, float noV) {
    f90 = clamp(f90, 0.0, 1.0);
    float peak = max(f90.r, max(f90.g, f90.b));
    f90 = peak > 0.0 ? f90 / peak : vec3(1.0);
    float edge = 1.2 * (1.0 - clamp(noV, 0.0, 1.0));
    float edgeSquared = edge * edge;
    vec3 grazing = mix(vec3(1.0), f90, edgeSquared * edgeSquared * edge);
    return f0 * mix(vec3(1.0), grazing, clamp(1.0 - 2.0 * roughness, 0.0, 1.0));
}
