#if defined(LFX_RECT_LIGHT_ATLAS)
uniform sampler2D LfxForwardRectAtlas;
#elif defined(LFX_RECT_LIGHT_TEXTURE)
uniform sampler2D LfxRectSourceTexture;
#endif

struct LfxRectTexture {
    vec4 uv;
    float maxMip;
};

vec3 lfx_rect_source_radiance(LfxRectLight rect, vec3 direction, LfxRectTexture source) {
#if defined(LFX_RECT_LIGHT_ATLAS) || defined(LFX_RECT_LIGHT_TEXTURE)
    direction += rect.axes[2] * clamp(0.001 - dot(rect.axes[2], direction), 0.0, 1.0);
    float distance = dot(rect.axes[2], rect.origin) / dot(rect.axes[2], direction);
    vec3 local = transpose(rect.axes) * (direction * distance - rect.origin);
    vec2 uv = (local.xy + rect.offset) / max(vec2(0.0001), rect.fullExtent) * vec2(0.5, -0.5) + 0.5;
    float level = log2(max(distance, 1e-6) / sqrt(max(0.0001, rect.fullExtent.x * rect.fullExtent.y)));
    uv = clamp(uv, 0.0, 1.0) * source.uv.xy + source.uv.zw;
#ifdef LFX_RECT_LIGHT_ATLAS
    if (source.maxMip >= 32.0) return vec3(1.0);
    ivec2 size = textureSize(LfxForwardRectAtlas, 0);
    vec2 slotSize = vec2(size) * source.uv.xy;
    level = clamp(level + log2(min(slotSize.x, slotSize.y)) - 2.0, 0.0, source.maxMip);
    vec2 border = 0.5 / vec2(size >> int(ceil(level)));
    return textureLod(LfxForwardRectAtlas, clamp(uv, source.uv.zw + border, source.uv.zw + source.uv.xy - border), level).rgb;
#else
    vec2 size = vec2(textureSize(LfxRectSourceTexture, 0));
    level += log2(min(size.x, size.y)) - 2.0;
    return textureLod(LfxRectSourceTexture, clamp(uv, 0.5 / size, 1.0 - 0.5 / size), level).rgb;
#endif
#else
    return vec3(1.0);
#endif
}

vec3 lfx_rect_source_radiance(LfxRectLight rect, vec3 direction, vec4 sourceUv) {
    return lfx_rect_source_radiance(rect, direction, LfxRectTexture(sourceUv, 32.0));
}
