vec3 lfx_toon_indirect_ramp(int profile, vec3 specular, float repetitions) {
    if (repetitions <= 0.0) return specular;
    float value = max(specular.r, max(specular.g, specular.b));
    float level = log(max(0.000001, value)) * repetitions;
    float mapped = exp((floor(level) + lfx_toon_ramp(profile, 3, fract(level)).r) / repetitions);
    return value > 0.0 ? specular * (mapped / value) : vec3(mapped);
}

vec3 lfx_toon_indirect_texture(int profile, vec3 specular, int mode, vec3 n, vec3 v, vec3 viewUp) {
    if (mode == 0) return specular;
    vec2 extent = vec2(textureSize(LfxToonProfiles, 0).xy);
    vec2 uv;
    if (mode == 1 || mode == 2) {
        vec3 localRight = normalize(cross(v, viewUp));
        vec3 localUp = normalize(cross(localRight, v));
        if (mode == 1) {
            vec2 localNormal = vec2(dot(n, localRight), dot(n, -localUp));
            uv = 0.5 + localNormal * (0.5 * (extent.x - 1.42) / extent.x);
        } else {
            vec3 ray = reflect(-v, n);
            ray = normalize(vec3(dot(ray, localRight), dot(ray, localUp), dot(ray, v)));
            uv = lfx_clear_coat_octahedron(ray) * 0.5 + 0.5;
        }
    } else {
        uv = lfx_clear_coat_octahedron(reflect(-v, n)) * 0.5 + 0.5;
    }
    vec2 texel = vec2(1.0, 6.0 + 2.0 * extent.x) + uv * (extent.x - 2.0);
    vec3 tint = textureLod(LfxToonProfiles, vec3(texel / extent, float(profile)), 0.0).rgb;
    return mix(specular * tint, specular + tint, lfx_toon_settings(profile, 4).x);
}
