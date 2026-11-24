uniform sampler2D LfxSpecularProfiles;

vec3 lfx_specular_profile_slice(float profile, vec2 uv, int slice) {
    int size = int(texelFetch(LfxSpecularProfiles, ivec2(0), 0).r);
    ivec2 extent = textureSize(LfxSpecularProfiles, 0);
    int columns = (extent.x - 1) / size;
    int tile = (int(abs(profile)) - 1) * 2 + slice;
    ivec2 origin = ivec2(1, 0) + size * ivec2(tile % columns, tile / columns);
    // Keep each tile's bilinear footprint within its native array slice.
    vec2 pixel = vec2(origin) + clamp(uv * float(size), vec2(0.5), vec2(float(size) - 0.5));
    return textureLod(LfxSpecularProfiles, pixel / vec2(extent), 0.0).rgb;
}

vec3 lfx_specular_profile_light(float profile, float noV, float noL, vec2 noHVoH) {
    if (profile == 0.0) return vec3(1.0);
    return lfx_specular_profile_slice(profile, profile > 0.0 ? vec2(noV, noL) : noHVoH.yx, 0);
}

vec3 lfx_specular_profile_environment(float profile, float noV, float roughness) {
    if (profile == 0.0) return vec3(1.0);
    return lfx_specular_profile_slice(profile, vec2(noV, roughness), 1);
}
