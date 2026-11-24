float lfx_substrate_haze_sqrt(float value) {
    return intBitsToFloat(0x1FBD1DF5 + (floatBitsToInt(value) >> 1));
}

float lfx_substrate_haze_extent(float roughness) {
    return mix(27.0, 3.0, clamp(4.0 * roughness, 0.0, 1.0));
}

float lfx_substrate_haze_roughness(float roughness) {
    return lfx_substrate_haze_sqrt((roughness * roughness) * (1.0 + lfx_substrate_haze_extent(roughness)));
}

float lfx_substrate_haze_weight(float roughness, float haziness) {
    float extent = 1.0 + lfx_substrate_haze_extent(roughness);
    float core = mix(0.01, 0.5, clamp(2.0 * roughness, 0.0, 1.0));
    float wide = (roughness * roughness) * extent;
    float fraction = 1.0 / (extent * extent);
    float b = 2.0 * (core * (1.0 - wide) + wide * fraction);
    float discriminant = b * b - 4.0 * (b - 1.0) * core;
    float u = (b - lfx_substrate_haze_sqrt(discriminant)) / (2.0 * b - 2.0);
    float u2 = u * u;
    float overlap = (u - u2) * (2.0 * wide);
    float inner = 1.0 - u;
    float haze = (overlap * haziness) / (inner * inner + overlap + u2);
    return haze / (core + (1.0 - fraction) * haze);
}
