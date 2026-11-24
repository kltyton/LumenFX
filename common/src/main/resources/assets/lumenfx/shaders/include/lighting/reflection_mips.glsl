float lfx_reflection_mip(float roughness, float mipCount) {
    float fromSmallest = 1.0 - 1.2 * log2(max(roughness, 0.001));
    return mipCount - 2.0 - fromSmallest;
}
