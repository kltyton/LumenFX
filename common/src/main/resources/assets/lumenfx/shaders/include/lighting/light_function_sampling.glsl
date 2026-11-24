vec3 lfx_sample_light_function(sampler2D atlas, mat4 translatedWorldToLight, vec4 parameters, vec4 tile,
                               vec3 position, float viewDistance) {
    const float pi = 3.14159265358979323846;
    vec4 light = translatedWorldToLight * vec4(position, 1.0);
    vec3 local = light.zyx / light.w;
    vec2 uv = local.xy;
    int type = int(parameters.w);
    if (type == 1 || type == 4) {
        vec3 direction = dot(local, local) > 0.0 ? normalize(local) : vec3(0.0, 0.0, 1.0);
        uv = vec2((atan(direction.y, direction.x) + pi) / (2.0 * pi), acos(clamp(direction.z, -1.0, 1.0)) / pi);
    } else if ((type == 2 || type == 3 || type == 5) && parameters.z > 0.0) {
        uv = uv / (local.z * parameters.z) * 0.5 + 0.5;
    }
    vec2 tileUv = type == 4 || type == 5 ? clamp(uv, 0.0, 1.0) : fract(uv);
    vec3 encoded = textureLod(atlas, tile.xy + tileUv * tile.z, 0.0).rgb;
    if (tile.w < 0.5) encoded = encoded.rrr;
    float fade = clamp((parameters.x - viewDistance) / (parameters.x * 0.2), 0.0, 1.0);
    return mix(vec3(parameters.y), encoded * encoded, fade);
}
