bool lfx_shaderpack_decal_depth_matches(float capturedDepth, float currentDepth) {
    return capturedDepth >= 0.0 && abs(capturedDepth - currentDepth) <= 0.000001;
}
