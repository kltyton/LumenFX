vec2 lfx_custom_scene_uv(vec2 uv) {
    return vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
}

#if (LFX_CUSTOM_SCENE_TEXTURES & 1) != 0
uniform sampler2D LfxMaterialCustomDepth;
float lfx_material_custom_depth(vec2 uv) {
    return lfx_linear_depth(texture(LfxMaterialCustomDepth, uv).r);
}
#endif

#if (LFX_CUSTOM_SCENE_TEXTURES & 2) != 0
uniform usampler2D LfxMaterialCustomStencil;
float lfx_material_custom_stencil(vec2 uv) {
    ivec2 extent = textureSize(LfxMaterialCustomStencil, 0);
    ivec2 pixel = ivec2(trunc(uv * vec2(extent)));
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) return 0.0;
    return float(texelFetch(LfxMaterialCustomStencil, pixel, 0).g);
}
#endif
