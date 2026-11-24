#version 330 core

layout(std140) uniform RectAtlasSlot {
    ivec4 RectAtlasDestination;
    vec4 RectAtlasCrop;
    ivec4 RectAtlasSourceMip;
};
uniform sampler2D RectAtlasInput;
out vec4 Radiance;

#if LFX_RECT_ATLAS_REDUCE && LFX_RECT_ATLAS_GAUSSIAN
vec3 rectAtlasTap(vec2 coordinate, vec2 minimum, vec2 maximum) {
    vec2 uv = clamp(coordinate, minimum + 0.5, maximum - 0.5) / vec2(textureSize(RectAtlasInput, 0));
    return textureLod(RectAtlasInput, uv, 0.0).rgb;
}
#endif

void main() {
#if !LFX_RECT_ATLAS_REDUCE
    vec2 uv = (gl_FragCoord.xy - vec2(RectAtlasDestination.xy)) / vec2(RectAtlasDestination.zw);
    vec4 value = textureLod(RectAtlasInput, uv * RectAtlasCrop.xy + RectAtlasCrop.zw, float(RectAtlasSourceMip.x));
    Radiance = max(mix(value, vec4(0.0), isnan(value)), vec4(0.0));
#elif LFX_RECT_ATLAS_GAUSSIAN
    vec2 center = gl_FragCoord.xy * 2.0;
    vec2 minimum = vec2(RectAtlasDestination.xy * 2);
    vec2 maximum = minimum + vec2(RectAtlasDestination.zw * 2);
    vec3 value = 0.125 * rectAtlasTap(center, minimum, maximum);
    for (int y = -1; y <= 1; y += 2) for (int x = -1; x <= 1; x += 2) {
        vec2 signPair = vec2(x, y);
        value += 0.125 * rectAtlasTap(center + signPair, minimum, maximum);
        value += 0.03125 * rectAtlasTap(center + 2.0 * signPair, minimum, maximum);
    }
    for (int side = -2; side <= 2; side += 4) {
        value += 0.0625 * rectAtlasTap(center + vec2(side, 0), minimum, maximum);
        value += 0.0625 * rectAtlasTap(center + vec2(0, side), minimum, maximum);
    }
    Radiance = vec4(value, 1.0);
#else
    ivec2 minimum = RectAtlasDestination.xy * 2;
    ivec2 maximum = minimum + RectAtlasDestination.zw * 2 - 1;
    ivec2 base = clamp(ivec2(gl_FragCoord.xy) * 2, minimum, maximum - 1);
    Radiance = 0.25 * (texelFetch(RectAtlasInput, base, 0) + texelFetch(RectAtlasInput, base + ivec2(1, 0), 0)
            + texelFetch(RectAtlasInput, base + ivec2(0, 1), 0) + texelFetch(RectAtlasInput, base + 1, 0));
#endif
}
