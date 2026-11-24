vec4 lfx_unity_cookie_cube(vec3 direction) {
    vec3 major = abs(direction);
    if (major.x >= max(major.y, major.z)) {
        float axis = max(major.x, 1e-6);
        if (direction.x >= 0.0)
            return texture(SamplerCookie0, vec2(-direction.z, -direction.y) * (0.5 / axis) + 0.5);
        return texture(SamplerCookie1, vec2(direction.z, -direction.y) * (0.5 / axis) + 0.5);
    }
    if (major.y >= major.z) {
        if (direction.y >= 0.0)
            return texture(SamplerCookie2, vec2(direction.x, direction.z) * (0.5 / major.y) + 0.5);
        return texture(SamplerCookie3, vec2(direction.x, -direction.z) * (0.5 / major.y) + 0.5);
    }
    if (direction.z >= 0.0)
        return texture(SamplerCookie4, vec2(direction.x, -direction.y) * (0.5 / major.z) + 0.5);
    return texture(SamplerCookie5, vec2(-direction.x, -direction.y) * (0.5 / major.z) + 0.5);
}
