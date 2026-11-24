#moj_import <lumenfx:lighting/clear_coat_normals.glsl>

float lfx_slab_legacy_clear_coat(float coverage, vec3 top, vec3 bottom) {
#if LFX_CLEAR_COAT_BOTTOM_NORMAL
    return float(coverage > 0.0 || any(notEqual(top, bottom)));
#else
    return float(coverage > 0.0);
#endif
}

vec2 lfx_quantize_slab_coat_normal(vec2 oct) {
    return floor(clamp(oct, 0.0, 1.0) * 255.0) / 255.0;
}

vec2 lfx_encode_slab_coat_normal(vec3 top, vec3 bottom) {
    vec2 delta = (lfx_clear_coat_octahedron(normalize(bottom)) - lfx_clear_coat_octahedron(normalize(top))) * 0.25;
    return lfx_quantize_slab_coat_normal(delta + 128.0 / 255.0);
}

vec3 lfx_decode_slab_coat_normal(uint packedValue, vec3 top) {
    vec2 delta = vec2(packedValue & 255u, (packedValue >> 8) & 255u) * (4.0 / 255.0) - 512.0 / 255.0;
    return lfx_clear_coat_unit_vector(lfx_clear_coat_octahedron(top) + delta);
}
