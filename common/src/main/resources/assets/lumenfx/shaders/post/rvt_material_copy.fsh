#version 330
#moj_import <lumenfx:rvt_material_output.glsl>

uniform sampler2D RvtRaster0;
#if LFX_RVT_RASTER_LAYERS > 1
uniform sampler2D RvtRaster1;
#endif
#if LFX_RVT_RASTER_LAYERS > 2
uniform sampler2D RvtRaster2;
#endif

#if LFX_RVT_RGB565
out uint fragColor;
#else
out vec4 fragColor;
#endif

vec4 lfx_rvt_copy(ivec2 pixel) {
#if LFX_RVT_LAYOUT == 0
    vec4 base = texelFetch(RvtRaster0, pixel, 0);
    return vec4(linearToSrgb(base.rgb), base.a);
#elif LFX_RVT_LAYOUT == 1
    return vec4(texelFetch(RvtRaster0, pixel, 0).rgb, texelFetch(RvtRaster1, pixel, 0).r);
#elif LFX_RVT_LAYOUT == 2
#if LFX_RVT_COPY_LAYER == 0
    return texelFetch(RvtRaster0, pixel, 0);
#else
    return texelFetch(RvtRaster1, pixel, 0);
#endif
#elif LFX_RVT_LAYOUT >= 3 && LFX_RVT_LAYOUT <= 5
    vec3 base = texelFetch(RvtRaster0, pixel, 0).rgb;
    vec3 normalMask = texelFetch(RvtRaster1, pixel, 0).rgb;
    vec3 surface = texelFetch(RvtRaster2, pixel, 0).rgb;
    surface.z = roundEven(surface.z);
#if LFX_RVT_LAYOUT == 3
    return LFX_RVT_COPY_LAYER == 0 ? vec4(linearToSrgb(base), normalMask.x) : vec4(surface, normalMask.y);
#else
    vec3 ycocg = lfx_rvt_ycocg(base);
    if (LFX_RVT_COPY_LAYER == 0) return vec4(ycocg.yz, 0.0, ycocg.x);
    if (LFX_RVT_COPY_LAYER == 1) return vec4(normalMask.xy, 0.0, 1.0);
    return vec4(surface, LFX_RVT_LAYOUT == 5 ? normalMask.z : 1.0);
#endif
#else
    return texelFetch(RvtRaster0, pixel, 0);
#endif
}

void main() {
    vec4 value = lfx_rvt_copy(ivec2(gl_FragCoord.xy));
#if LFX_RVT_RGB565
    uvec3 words = uvec3(roundEven(clamp(value.rgb, 0.0, 1.0) * vec3(31.0, 63.0, 31.0)));
    fragColor = (words.r << 11u) | (words.g << 5u) | words.b;
#else
    fragColor = value;
#endif
}
