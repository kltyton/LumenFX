layout(std140) uniform DirectionalShadowCaster {
    vec4 CsmCasterBias;
    vec4 CsmCasterClipToView;
    vec4 CsmCasterLightDirection;
};

float lfx_csm_bias(float normalDotLight) {
    float maxSlope = max(0.0, CsmCasterBias.z);
    float slope = normalDotLight > 0.0
            ? sqrt(clamp(1.0 - normalDotLight * normalDotLight, 0.0, 1.0)) / normalDotLight : maxSlope;
    return CsmCasterBias.x + CsmCasterBias.y * clamp(slope, 0.0, maxSlope);
}

#ifdef LFX_CSM_CASTER_VERTEX
out vec3 lfxCsmUnclampedPosition;

vec4 lfx_csm_vertex_clip(vec4 clip) {
    lfxCsmUnclampedPosition = clip.xyz * CsmCasterClipToView.xyz;
    if (clip.z > clip.w) clip.z = 0.999999 * clip.w;
    return clip;
}

vec4 lfx_csm_vertex_clip(vec4 clip, float normalDotLight) {
    clip = lfx_csm_vertex_clip(clip);
    clip.z -= lfx_csm_bias(normalDotLight) * CsmCasterBias.w * clip.w;
    return clip;
}
#else
in vec3 lfxCsmUnclampedPosition;

float lfx_csm_geometry_bias() {
    vec3 normal = cross(dFdx(lfxCsmUnclampedPosition), dFdy(lfxCsmUnclampedPosition));
    float lengthSquared = dot(normal, normal);
    return lfx_csm_bias(lengthSquared > 0.0 ? abs(normal.z) * inversesqrt(lengthSquared) : 0.0);
}
#endif
