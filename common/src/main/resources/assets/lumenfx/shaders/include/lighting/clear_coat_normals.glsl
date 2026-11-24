vec2 lfx_clear_coat_octahedron(vec3 direction) {
    vec2 uv = direction.xy / dot(abs(direction), vec3(1.0));
    if (direction.z < 0.0) uv = (1.0 - abs(uv.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), uv));
    return uv;
}

vec3 lfx_clear_coat_unit_vector(vec2 oct) {
    vec3 direction = vec3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (direction.z < 0.0) direction.xy = (1.0 - abs(direction.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), direction.xy));
    return normalize(direction);
}
