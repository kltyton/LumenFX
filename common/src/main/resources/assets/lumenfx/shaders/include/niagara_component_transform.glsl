#ifndef LFX_NIAGARA_COMPONENT_TRANSFORM
#define LFX_NIAGARA_COMPONENT_TRANSFORM

mat3 lfx_component_rotation(vec4 q) {
    float x2 = q.x + q.x, y2 = q.y + q.y, z2 = q.z + q.z;
    float xx = q.x * x2, xy = q.x * y2, xz = q.x * z2;
    float yy = q.y * y2, yz = q.y * z2, zz = q.z * z2;
    float wx = q.w * x2, wy = q.w * y2, wz = q.w * z2;
    return mat3(1.0 - (yy + zz), xy + wz, xz - wy,
                xy - wz, 1.0 - (xx + zz), yz + wx,
                xz + wy, yz - wx, 1.0 - (xx + yy));
}

mat3 lfx_component_linear(vec4 rotation, vec3 scale) {
    mat3 basis = lfx_component_rotation(rotation);
    return mat3(basis[0] * scale.x, basis[1] * scale.y, basis[2] * scale.z);
}

mat3 lfx_component_minecraft_linear(vec4 sourceX, vec4 sourceY, vec4 sourceZ) {
    return mat3(sourceX.xzy, sourceZ.xzy, sourceY.xzy);
}

vec3 lfx_component_inverse_scale(mat3 linear) {
    vec3 scale = vec3(length(linear[0]), length(linear[1]), length(linear[2]));
    return vec3(scale.x > 0.0001 ? 1.0 / scale.x : 0.0,
                scale.y > 0.0001 ? 1.0 / scale.y : 0.0,
                scale.z > 0.0001 ? 1.0 / scale.z : 0.0);
}

vec3 lfx_component_ribbon_previous_inverse_scale(mat3 linear) {
    return inversesqrt(max(vec3(0.0001), vec3(dot(linear[0], linear[0]),
            dot(linear[1], linear[1]), dot(linear[2], linear[2]))));
}

#endif
