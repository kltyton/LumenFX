#if LFX_EXPONENTIAL_FOG
#moj_import <lumenfx:height_fog.glsl>
#if LFX_VOLUMETRIC_FOG
#moj_import <lumenfx:volume_fog.glsl>
#endif
vec4 lfx_scene_fog(vec3 cameraRelativePosition) {
#if LFX_VOLUMETRIC_FOG
    return lfx_height_fog(cameraRelativePosition, lfx_volume_height_exclusion(cameraRelativePosition));
#else
    return lfx_height_fog(cameraRelativePosition, 0.0);
#endif
}
#else
#moj_import <lumenfx:host_fog.glsl>
vec4 lfx_scene_fog(vec3 cameraRelativePosition) {
    return lfx_host_fog(cameraRelativePosition);
}
#endif

#if LFX_VERTEX_STAGE
#if LFX_AERIAL_PERSPECTIVE
#moj_import <lumenfx:lighting/aerial_perspective.glsl>
#endif
vec4 lfx_vertex_fog(vec3 cameraRelativePosition) {
    vec4 fog = lfx_scene_fog(cameraRelativePosition);
#if LFX_AERIAL_PERSPECTIVE
    fog = lfx_apply_aerial_perspective(fog, cameraRelativePosition);
#endif
    return fog;
}
#endif
