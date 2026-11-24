#moj_import <lumenfx:lighting/clear_coat_normals.glsl>

mat3 lfx_substrate_tangent_basis(vec3 normal) {
    float zSign = normal.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (zSign + normal.z);
    float b = normal.x * normal.y * a;
    return mat3(
        vec3(1.0 + zSign * a * normal.x * normal.x, zSign * b, -zSign * normal.x),
        vec3(b, zSign + a * normal.y * normal.y, -normal.y),
        normal);
}

uint lfx_substrate_pack_normal_tangent(vec3 normal, vec3 tangent) {
    uvec2 oct = uvec2(round(lfx_clear_coat_octahedron(normal) * 1023.0 + 1023.0));
    mat3 basis = lfx_substrate_tangent_basis(lfx_clear_coat_unit_vector(vec2(oct) / 1023.0 - 1.0));
    vec2 projected = vec2(dot(basis[0], tangent), dot(basis[1], tangent));
    float angle = abs(projected.y) / (abs(projected.x) + abs(projected.y));
    uint packedValue = oct.x | (oct.y << 11) | (uint(round(angle * 255.0)) << 22);
    return packedValue | ((floatBitsToUint(projected.x) >> 1) & 0x40000000u) | (floatBitsToUint(projected.y) & 0x80000000u);
}

void lfx_substrate_unpack_normal_tangent(vec4 encoded, out vec3 normal, out vec3 tangent) {
    uint packedValue = uint(encoded.x) | (uint(encoded.y) << 16);
    vec2 oct = vec2(packedValue & 2047u, (packedValue >> 11) & 2047u) / 1023.0 - 1.0;
    normal = lfx_clear_coat_unit_vector(oct);
    float y = float((packedValue >> 22) & 255u) / 255.0;
    vec2 projected = vec2(uintBitsToFloat(floatBitsToUint(1.0 - y) | ((packedValue << 1) & 0x80000000u)),
        uintBitsToFloat(floatBitsToUint(y) | (packedValue & 0x80000000u)));
    mat3 basis = lfx_substrate_tangent_basis(normal);
    tangent = basis * vec3(normalize(projected), 0.0);
}
