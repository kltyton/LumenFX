#ifndef LFX_SCENE_COLOR
#define LFX_SCENE_COLOR 0
#endif

#if LFX_SCENE_COLOR
uniform sampler2D LfxSceneColor;
#endif

vec2 lfx_scene_color_default_uv() {
    return gl_FragCoord.xy * vec2(Geometry.w, CameraUp.w);
}

vec2 lfx_scene_color_host_uv(vec2 viewportUV) {
    return vec2(viewportUV.x, CameraRight.w > 0.5 ? 1.0 - viewportUV.y : viewportUV.y);
}

vec2 lfx_scene_color_uv(vec2 viewportUV) {
    vec2 halfTexel = 0.5 * vec2(Geometry.w, CameraUp.w);
    return clamp(lfx_scene_color_host_uv(viewportUV), halfTexel, vec2(1.0) - halfTexel);
}

vec2 lfx_scene_color_offset_uv(vec2 offsetFraction) {
    vec2 viewportUV = lfx_scene_color_host_uv(lfx_scene_color_default_uv());
    return lfx_scene_color_host_uv(clamp(viewportUV + offsetFraction, vec2(0.0005), vec2(0.9995)));
}

vec4 lfx_material_scene_color(vec2 uv) {
#if LFX_SCENE_COLOR
    vec4 encoded = texture(LfxSceneColor, uv);
    return vec4(pow(encoded.rgb, vec3(4.0)) * 10.0 * MaterialCamera.z, encoded.a);
#else
    // The opaque base pass has no SceneColorCopyTexture alias in the source shader.
    return vec4(0.0);
#endif
}
