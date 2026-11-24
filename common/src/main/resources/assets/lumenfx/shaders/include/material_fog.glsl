#if LFX_NANITE_MATERIAL
vec3 effectPosition;
#else
in vec3 effectPosition;
#endif
#if LFX_APPLY_FOG
#moj_import <lumenfx:scene_fog.glsl>
#if LFX_PIXEL_FOG && LFX_AERIAL_PERSPECTIVE
#moj_import <lumenfx:lighting/aerial_perspective.glsl>
#endif
#if !LFX_PIXEL_FOG
in vec4 effectFog;
#endif
#endif

vec4 lfx_material_fog() {
#if LFX_APPLY_FOG
#if LFX_PIXEL_FOG
    vec4 fog = lfx_scene_fog(effectPosition);
#else
    vec4 fog = effectFog;
#endif
#if LFX_VOLUMETRIC_FOG
    fog = lfx_combine_volume(fog, effectPosition);
#endif
#if LFX_PIXEL_FOG && LFX_AERIAL_PERSPECTIVE
    fog = lfx_apply_aerial_perspective(fog, effectPosition);
#endif
    return fog;
#else
    return vec4(0.0, 0.0, 0.0, 1.0);
#endif
}

vec4 lfx_apply_material_fog(vec4 color) {
#if LFX_APPLY_FOG
    vec4 fog = lfx_material_fog();
#if LFX_FOG_BLEND == 1
    color.rgb *= fog.a;
#elif LFX_FOG_BLEND == 2
    color.rgb = color.rgb * fog.a + fog.rgb * color.a;
#else
    color.rgb = color.rgb * fog.a + fog.rgb;
#endif
#endif
    return color;
}
