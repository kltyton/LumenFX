uniform sampler2D LfxRectGgxMatrix;
uniform sampler2D LfxRectGgxAmplitude;
#moj_import <lumenfx:lighting/rect_light_source.glsl>

vec3 lfx_rect_ltc_edge(vec3 start, vec3 end) {
    float cosine = dot(start, end);
    float absoluteCosine = abs(cosine);
    float weight = (0.8543985 + (0.4965155 + 0.0145206 * absoluteCosine) * absoluteCosine)
            / (3.4175940 + (4.1616724 + absoluteCosine) * absoluteCosine);
    if (cosine <= 0.0) weight = 0.5 * inversesqrt(max(1.0 - cosine * cosine, 1e-4)) - weight;
    return weight * cross(start, end);
}

vec3 lfx_rect_ltc(LfxRectLight rect, vec3 normal, vec3 view, mat3 coefficients, mat3 inverseCoefficients,
                  vec3 amplitude, LfxRectTexture source) {
    if (any(lessThanEqual(rect.extent, vec2(0.0)))) return vec3(0.0);
    vec3 tangent = view - normal * dot(normal, view);
    if (dot(tangent, tangent) < 1e-12)
        tangent = cross(abs(normal.z) < 0.99 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0), normal);
    tangent = normalize(tangent);
    mat3 basis = mat3(tangent, cross(normal, tangent), normal);
    mat3 transform = coefficients * transpose(basis);
    vec3 corner[4];
    lfx_rect_corners(rect, corner);
    for (int index = 0; index < 4; ++index) corner[index] = normalize(transform * corner[index]);
    vec3 light = lfx_rect_ltc_edge(corner[0], corner[1]) + lfx_rect_ltc_edge(corner[1], corner[2])
            + lfx_rect_ltc_edge(corner[2], corner[3]) + lfx_rect_ltc_edge(corner[3], corner[0]);
    float solidAngle = length(light);
    light /= max(solidAngle, 1e-12);
    float irradiance = max(0.0, solidAngle * lfx_point_sphere_horizon(light.z, solidAngle));
    vec3 meanDirection = basis * inverseCoefficients * light;
    return lfx_rect_source_radiance(rect, meanDirection, source) * (amplitude * irradiance);
}

vec3 lfx_rect_ggx(LfxRectLight rect, vec3 normal, vec3 view, float roughness, vec3 f0, vec3 f90, LfxRectTexture source) {
    float noV = clamp(abs(dot(normal, view)) + 1e-5, 0.0, 1.0);
    vec2 uv = vec2(roughness, sqrt(1.0 - noV)) * (63.0 / 64.0) + (0.5 / 64.0);
    vec4 table = textureLod(LfxRectGgxMatrix, uv, 0.0);
    vec2 energy = textureLod(LfxRectGgxAmplitude, uv, 0.0).xy;
    mat3 coefficients = mat3(vec3(table.x, 0.0, table.y), vec3(0.0, 1.0, 0.0), vec3(table.z, 0.0, table.w));
    float determinant = table.x * table.w - table.y * table.z;
    if (abs(determinant) < 1e-12) return vec3(0.0);
    vec4 inverseTable = table / determinant;
    mat3 inverseCoefficients = mat3(vec3(inverseTable.w, 0.0, -inverseTable.y),
            vec3(0.0, 1.0, 0.0), vec3(-inverseTable.z, 0.0, inverseTable.x));
    return lfx_rect_ltc(rect, normal, view, coefficients, inverseCoefficients, f90 * energy.y + (energy.x - energy.y) * f0, source);
}

vec3 lfx_rect_legacy_ggx(LfxRectLight rect, vec3 normal, vec3 view, float roughness, vec3 f0, LfxRectTexture source) {
    return lfx_rect_ggx(rect, normal, view, max(roughness, 0.02), f0, clamp(50.0 * f0, 0.0, 1.0), source);
}

#ifndef LFX_RECT_LIGHT_ATLAS
vec3 lfx_rect_source_radiance(LfxRectLight rect, vec3 direction) {
    return lfx_rect_source_radiance(rect, direction, PointRectSourceUv);
}

vec3 lfx_rect_ggx(LfxRectLight rect, vec3 normal, vec3 view, float roughness, vec3 f0, vec3 f90) {
    return lfx_rect_ggx(rect, normal, view, roughness, f0, f90, LfxRectTexture(PointRectSourceUv, 32.0));
}

vec3 lfx_rect_legacy_ggx(LfxRectLight rect, vec3 normal, vec3 view, float roughness, vec3 f0) {
    return lfx_rect_legacy_ggx(rect, normal, view, roughness, f0, LfxRectTexture(PointRectSourceUv, 32.0));
}

vec3 lfx_rect_sheen(LfxRectLight rect, vec3 normal, vec3 view, vec3 table) {
    if (table.x <= 0.0) return vec3(0.0);
    mat3 coefficients = mat3(vec3(table.x, 0.0, 0.0), vec3(0.0, table.x, 0.0), vec3(table.y, 0.0, 1.0));
    mat3 inverseCoefficients = mat3(vec3(1.0 / table.x, 0.0, 0.0), vec3(0.0, 1.0 / table.x, 0.0),
            vec3(-table.y / table.x, 0.0, 1.0));
    return lfx_rect_ltc(rect, normal, view, coefficients, inverseCoefficients, vec3(table.z), LfxRectTexture(PointRectSourceUv, 32.0));
}
#endif
