vec3 lfx_clear_coat_refract(float noV, float noL, vec2 halfVector) {
    const float eta = 1.0 / 1.5;
    float refraction = (0.63 - 0.22 * halfVector.y) * halfVector.y - 0.745;
    float projection = refraction * halfVector.x;
    return vec3(clamp(eta * noV - projection, 0.001, 1.0), clamp(eta * noL - projection, 0.001, 1.0),
            clamp(eta * halfVector.y - refraction, 0.0, 1.0));
}

vec3 lfx_clear_coat_transmittance(vec2 transmitted, float metallic, vec3 baseColor) {
    vec3 extinction = -0.5 * log(max(baseColor / LFX_BRDF_PI, vec3(1e-4)));
    float pathLength = max(1.0 / transmitted.x + 1.0 / transmitted.y - 2.0, 0.0);
    return mix(vec3(1.0), exp(-extinction * pathLength), metallic);
}
