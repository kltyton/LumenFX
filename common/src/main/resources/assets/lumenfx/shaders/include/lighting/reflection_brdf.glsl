vec2 lfx_environment_brdf_analytic(float roughness, float noV) {
    vec4 r = roughness * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
    float a004 = min(r.x * r.x, exp2(-9.28 * noV)) * r.x + r.y;
    return vec2(-1.04, 1.04) * a004 + r.zw;
}

vec3 lfx_environment_brdf(vec2 gf, vec3 f0) {
    return f0 * gf.x + clamp(50.0 * f0.g, 0.0, 1.0) * gf.y;
}
