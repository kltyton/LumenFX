uniform sampler2D LfxSheenLtc;

vec3 lfx_substrate_sheen_ltc_coefficients(float noV, float roughness) {
    vec2 uv = (vec2(sqrt(clamp(roughness, 0.02, 1.0)), clamp(abs(noV) + 1e-5, 0.0, 1.0)) * 31.0 + 0.5) / 32.0;
    return textureLod(LfxSheenLtc, uv, 0.0).rgb;
}

// The LTC density already includes the cosine of the incoming direction.
float lfx_substrate_sheen_ltc(vec3 normal, vec3 view, vec3 direction, float roughness, out float albedo) {
    float noV = clamp(max(abs(dot(normal, view)), 1e-10), 0.0, 1.0);
    vec3 coefficients = lfx_substrate_sheen_ltc_coefficients(noV, roughness);
    albedo = coefficients.z;
    vec3 projectedView = view - normal * noV;
    float projectedLength = dot(projectedView, projectedView);
    vec3 tangent = projectedLength > 0.0 ? projectedView * inversesqrt(projectedLength)
            : normalize(cross(abs(normal.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0), normal));
    vec3 bitangent = cross(normal, tangent);
    vec3 local = vec3(dot(tangent, direction), dot(bitangent, direction), dot(normal, direction));
    vec3 wrapped = vec3(coefficients.x * local.x + coefficients.y * local.z, coefficients.x * local.y, local.z);
    float jacobianRoot = coefficients.x / dot(wrapped, wrapped);
    return max(wrapped.z * jacobianRoot * jacobianRoot / LFX_BRDF_PI, 0.0);
}
