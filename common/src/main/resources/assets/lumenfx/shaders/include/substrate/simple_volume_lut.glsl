vec2 lfx_simple_volume_plane_uv(sampler2D lut, vec3 coordinate, int depth, float slice) {
    float atlasHeight = float(textureSize(lut, 0).y);
    float sliceStride = atlasHeight / float(depth);
    float row = clamp(coordinate.y * 11.0, 0.5, 10.5);
    return vec2(coordinate.x, (sliceStride * slice + row) / atlasHeight);
}

float lfx_simple_volume_sample(sampler2D lut, vec3 coordinate, int depth) {
    float slice = clamp(coordinate.z * float(depth) - 0.5, 0.0, float(depth - 1));
    float lower = floor(slice);
    float upper = min(lower + 1.0, float(depth - 1));
    float a = textureLod(lut, lfx_simple_volume_plane_uv(lut, coordinate, depth, lower), 0.0).r;
    float b = textureLod(lut, lfx_simple_volume_plane_uv(lut, coordinate, depth, upper), 0.0).r;
    return mix(a, b, slice - lower);
}

float lfx_simple_volume_angle(float cosine) {
    float value = clamp(cosine, 0.0, 1.0);
    return clamp((1.5707963267948966 - 0.156583 * value) * sqrt(1.0 - value) / 1.5707963267948966, 0.0, 1.0);
}

float lfx_simple_volume_punctual(sampler2D lut, float meanFreePath, float baseColor, float viewAngle, float lightAngle) {
    vec2 uv = (vec2(meanFreePath, 10.0 * baseColor) + 0.5) / 11.0;
    float viewSlice = viewAngle * 8.0;
    float lower = min(floor(viewSlice), 7.0);
    float upper = min(lower + 1.0, 7.0);
    float lightSlice = 0.5 + 8.0 * lightAngle;
    float a = lfx_simple_volume_sample(lut, vec3(uv, (9.0 * lower + lightSlice) / 72.0), 72);
    float b = lfx_simple_volume_sample(lut, vec3(uv, (9.0 * upper + lightSlice) / 72.0), 72);
    float fade = clamp(1.0 - (meanFreePath - 10.0) / 630.0, 0.0, 1.0);
    return mix(a, b, clamp(viewSlice - lower, 0.0, 1.0)) * fade;
}

vec3 lfx_simple_volume_punctual(sampler2D lut, vec3 meanFreePath, vec3 baseColor, float noV, float noL) {
    float viewAngle = lfx_simple_volume_angle(noV);
    float lightAngle = lfx_simple_volume_angle(noL);
    return vec3(
        lfx_simple_volume_punctual(lut, meanFreePath.r, baseColor.r, viewAngle, lightAngle),
        lfx_simple_volume_punctual(lut, meanFreePath.g, baseColor.g, viewAngle, lightAngle),
        lfx_simple_volume_punctual(lut, meanFreePath.b, baseColor.b, viewAngle, lightAngle)
    );
}
