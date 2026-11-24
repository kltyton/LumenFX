uniform sampler2D LfxSlabPunctualVolume;

vec3 lfx_slab_vertical_coverage(float top, float bottom) {
    float bottomOnly = (1.0 - top) * bottom;
    return vec3(top * bottom, bottomOnly, top + bottomOnly);
}

vec3 lfx_slab_vertical_transmittance(vec3 meanFreePath, float f0, float noV) {
    float eta = 1.0 / (2.0 / (1.0 - sqrt(min(f0, 0.99))) - 1.0);
    float localX = intBitsToFloat(0x1fbd1df5 + (floatBitsToInt(1.0 - noV) >> 1));
    vec3 refracted = refract(vec3(localX, 0.0, noV), vec3(0.0, 0.0, -1.0), eta);
    float viewPath = 1.0 / max(0.0001, abs(refracted.z));
    vec3 extinction = max(vec3(0.000001), 1.0 / max(meanFreePath, vec3(1e-12)));
    return exp(-extinction * viewPath) * exp(-extinction);
}
