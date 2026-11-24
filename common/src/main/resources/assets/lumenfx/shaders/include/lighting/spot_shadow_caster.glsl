layout(std140) uniform SpotShadowCaster {
    vec4 SpotCasterProjection;
    vec4 SpotCasterBias;
};

float lfx_spot_caster_bias() {
    vec2 ndc = gl_FragCoord.xy * (2.0 * SpotCasterProjection.z) - 1.0;
    vec3 position = vec3(ndc * SpotCasterProjection.w, 1.0) / gl_FragCoord.w;
    vec3 normal = cross(dFdx(position), dFdy(position));
    float slope = min(length(normal.xy) / max(abs(normal.z), 1e-20), SpotCasterBias.z);
    return SpotCasterBias.x + SpotCasterBias.y * slope;
}

float lfx_spot_caster_depth(float deviceDepth, float bias) {
    float viewDepth = SpotCasterProjection.y / (deviceDepth - SpotCasterProjection.x);
    float nearPlane = SpotCasterProjection.y / (1.0 - SpotCasterProjection.x);
    float linearDepth = (SpotCasterProjection.x * viewDepth + SpotCasterProjection.y) / nearPlane;
    return clamp(linearDepth - bias, 0.0, 1.0);
}
