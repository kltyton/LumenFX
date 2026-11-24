uniform sampler2DArray LfxToonProfiles;

int lfx_toon_profile_layer(vec4 surface) {
    return int(surface.a);
}

vec2 lfx_toon_pattern_uv(vec4 surface) {
    return surface.xy / 4095.0;
}

vec4 lfx_toon_settings(int layer, int column) {
    return texelFetch(LfxToonProfiles, ivec3(column, 4, layer), 0);
}

vec3 lfx_toon_ramp(int layer, int row, float value) {
    float height = float(textureSize(LfxToonProfiles, 0).y);
    return textureLod(LfxToonProfiles, vec3(value, (float(row) + 0.5) / height, float(layer)), 0.0).rgb;
}

vec4 lfx_toon_pattern(int layer, int tile, vec2 uv) {
    vec2 extent = vec2(textureSize(LfxToonProfiles, 0).xy);
    vec2 origin = vec2(0.0, 5.0 + float(tile) * extent.x) + 1.0;
    return textureLod(LfxToonProfiles, vec3((origin + fract(uv) * (extent.x - 2.0)) / extent, float(layer)), 0.0);
}

float lfx_toon_diffuse_offset(int layer, vec2 patternUv, vec4 settings) {
    if (settings.x <= 0.0) return 0.0;
    return settings.x * (lfx_toon_pattern(layer, 0, patternUv * settings.y).x * 2.0 - 1.0);
}

float lfx_toon_specular_offset(int layer, vec2 patternUv, vec4 settings) {
    if (settings.z <= 0.0) return 0.0;
    return settings.z * (lfx_toon_pattern(layer, 0, patternUv * settings.w).y * 2.0 - 1.0);
}

float lfx_toon_shadow_hatching(int layer, vec2 patternUv, float transmittance) {
    vec4 settings = lfx_toon_settings(layer, 2);
    if (settings.z <= 0.0) return transmittance;
    vec4 pattern = lfx_toon_pattern(layer, 1, patternUv * settings.y);
    float distribution = lfx_toon_ramp(layer, 2, transmittance).r;
    float shade = 0.0;
    if (distribution <= 0.25) shade = mix(pattern.a, pattern.b, clamp(distribution * 4.0, 0.0, 1.0));
    else if (distribution <= 0.50) shade = mix(pattern.b, pattern.g, distribution * 4.0 - 1.0);
    else if (distribution <= 0.75) shade = mix(pattern.g, pattern.r, distribution * 4.0 - 2.0);
    else if (distribution <= 1.00) shade = mix(pattern.r, 1.0, distribution * 4.0 - 3.0);
    return mix(1.0, shade, settings.z);
}
