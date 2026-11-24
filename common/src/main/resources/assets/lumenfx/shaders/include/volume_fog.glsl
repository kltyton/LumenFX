#moj_import <lumenfx:fog_volume_uniforms.glsl>

float lfx_volume_height_exclusion(vec3 cameraRelativePosition) {
#if LFX_PIXEL_FOG
    float rayLength = length(cameraRelativePosition);
    float viewDepth = dot(cameraRelativePosition.xzy, FogViewForward.xyz);
    return viewDepth > rayLength * 1.1920929e-7 ? FogVolumeRange.x * (rayLength / viewDepth) : 0.0;
#else
    return FogVolumeRange.x;
#endif
}

#if !LFX_VERTEX_STAGE
#moj_import <lumenfx:volumetric_common.glsl>
uniform sampler2D FogVolumeSampler;

vec4 lfx_combine_volume(vec4 heightFog, vec3 cameraRelativePosition) {
    vec4 clip = FogWorldToClip * vec4(cameraRelativePosition, 1.0);
    float depthCm = clip.w * 100.0;
    if (depthCm <= FogVolumeRange.y) return heightFog;
    vec2 screen = min(lfx_volume_uv(clip), FogUvBounds.xy);
    float slice = lfx_slice_from_depth(depthCm);
    vec4 volume = lfx_sample_atlas(FogVolumeSampler, vec3(screen * FogGrid.xy, slice), FogGrid, FogAtlas);
    volume.rgb /= FogHistoryBounds.w;
    volume = mix(vec4(0.0, 0.0, 0.0, 1.0), volume, clamp((depthCm - FogVolumeRange.y) * 100000000.0, 0.0, 1.0));
    return vec4(volume.rgb + heightFog.rgb * volume.a, volume.a * heightFog.a);
}
#endif
