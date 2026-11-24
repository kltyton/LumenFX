#if LFX_MATERIAL_BASIS || defined(LFX_CSM_PARTICLE_RIBBON)
vec3 lfx_basis_safe_normalize(vec3 value, vec3 fallbackValue) {
    float square = dot(value, value);
    return square > 0.0 ? value * inversesqrt(square) : fallbackValue;
}

vec3 lfx_ribbon_world_normal(vec3 direction, vec3 right, vec3 facing, vec2 sliceNormal, bool geometryNormals) {
    vec3 baseNormal = geometryNormals ? -cross(direction, right) : facing;
    return lfx_basis_safe_normalize(baseNormal * sliceNormal.y + right * sliceNormal.x, vec3(0.0, 1.0, 0.0));
}

void lfx_ribbon_material_basis(vec3 center, vec3 direction, vec3 right, vec3 facing, vec2 sliceNormal,
                               bool geometryNormals, bool flipToView, out vec3 normal, out vec3 tangent) {
    vec3 worldNormal = lfx_ribbon_world_normal(direction, right, facing, sliceNormal, geometryNormals);
    vec3 viewNormal = lfx_basis_safe_normalize(-center, vec3(0.0));
    if (flipToView && dot(viewNormal, worldNormal) < 0.0) worldNormal = -worldNormal;
    normal = worldNormal.xzy;
    tangent = direction.xzy;
}
#endif
