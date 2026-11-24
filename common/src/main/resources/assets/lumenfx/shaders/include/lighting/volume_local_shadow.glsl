#if defined(LFX_VOLUME_SPOT_SHADOW) || defined(LFX_VOLUME_CUBE_SHADOW)
layout(std140) uniform VolumeLocalShadow {
    mat4 VolumeLocalShadowProjection[6];
    vec4 VolumeLocalShadowParameters;
    vec4 VolumeLocalShadowStrength;
};
#endif
#ifdef LFX_VOLUME_SPOT_SHADOW
#moj_import <lumenfx:lighting/spot_light_shadow.glsl>
uniform sampler2D VolumeLocalShadowDepth;
#elif defined(LFX_VOLUME_CUBE_SHADOW)
#moj_import <lumenfx:lighting/point_light_shadow.glsl>
uniform samplerCubeShadow VolumeLocalShadowDepth;
#endif

float lfx_volume_local_shadow(vec3 toLight, float inverseRadius) {
#ifdef LFX_VOLUME_SPOT_SHADOW
    vec4 clip = VolumeLocalShadowProjection[0] * vec4(-toLight, 1.0);
    if (clip.w <= 0.0 || clip.z > clip.w) return 1.0;
    vec2 uv = lfx_spot_shadow_uv(clip);
    vec2 border = vec2(4.0 * VolumeLocalShadowParameters.x);
    if (any(lessThan(uv, border)) || any(greaterThan(uv, 1.0 - border))) return 1.0;
    float receiver = lfx_spot_shadow_depth(clip.w, VolumeLocalShadowParameters.zw);
    float caster = lfx_spot_shadow_sample(VolumeLocalShadowDepth, uv);
    return caster == 1.0 || receiver < caster - VolumeLocalShadowParameters.y ? 1.0 : 1.0 - VolumeLocalShadowStrength.x;
#elif defined(LFX_VOLUME_CUBE_SHADOW)
    if (dot(toLight, toLight) <= 0.0) return 1.0;
    float visibility = lfx_point_shadow_pcf(VolumeLocalShadowDepth, VolumeLocalShadowProjection, VolumeLocalShadowParameters.x,
            -toLight, vec3(0.0), inverseRadius, VolumeLocalShadowParameters.y, 0.0);
    return mix(1.0, visibility, VolumeLocalShadowStrength.x);
#else
    return 1.0;
#endif
}
