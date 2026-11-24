#ifndef LFX_DIRECTIONAL_CASCADE_LIMIT
#define LFX_DIRECTIONAL_CASCADE_LIMIT 4
#endif
layout(std140) uniform LfxForwardDirectionalShadow {
    mat4 ForwardWorldToShadow[LFX_DIRECTIONAL_CASCADE_LIMIT];
    vec4 ForwardShadowBounds[LFX_DIRECTIONAL_CASCADE_LIMIT];
    vec4 ForwardCascadeRange[LFX_DIRECTIONAL_CASCADE_LIMIT];
    ivec4 ForwardShadowSettings;
    vec4 ForwardShadowFade;
    mat4 StaticDirectionalWorldToShadow;
    ivec4 StaticDirectionalShadowSettings;
};
uniform sampler2D LfxForwardDirectionalDepth;
uniform sampler2D LfxStaticDirectionalDepth;

bool lfx_static_directional_coordinates(vec3 position, out vec2 uv, out float depth) {
    if (StaticDirectionalShadowSettings.x == 0) return false;
    vec4 projected = StaticDirectionalWorldToShadow * vec4(position, 1.0);
    uv = projected.xy / projected.w;
    depth = projected.z;
    return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

float lfx_forward_directional_static(vec3 position) {
    vec2 uv;
    float depth;
    if (!lfx_static_directional_coordinates(position, uv, depth)) return 1.0;
    float blocker = textureLod(LfxStaticDirectionalDepth, uv, 0.0).r;
    return float(depth < blocker || blocker > 0.99);
}

float lfx_forward_directional_visibility(ivec2 texel, float receiverDepth) {
    float blockerDepth = texelFetch(LfxForwardDirectionalDepth, texel, 0).r;
    return clamp((receiverDepth - blockerDepth) * 4000.0 + 1.0, 0.0, 1.0);
}

float lfx_forward_directional_shadow(vec3 position, float sceneDepth) {
    int count = ForwardShadowSettings.x;
    int cascade = 0;
    while (cascade < count && sceneDepth >= ForwardCascadeRange[cascade].y) cascade++;
    if (cascade == count) return 1.0;
    vec4 samplePosition = ForwardWorldToShadow[cascade] * vec4(position, 1.0);
    vec2 uv = samplePosition.xy / samplePosition.w;
    vec4 bounds = ForwardShadowBounds[cascade];
    if (any(lessThan(uv, bounds.xy)) || any(greaterThan(uv, bounds.zw))) return 1.0;
    vec2 sampleTexel = uv * vec2(textureSize(LfxForwardDirectionalDepth, 0)) - 0.5;
    ivec2 lower = ivec2(floor(sampleTexel));
    vec2 weight = fract(sampleTexel);
    float depth = samplePosition.z;
    float left = mix(lfx_forward_directional_visibility(lower, depth),
            lfx_forward_directional_visibility(lower + ivec2(0, 1), depth), weight.y);
    float right = mix(lfx_forward_directional_visibility(lower + ivec2(1, 0), depth),
            lfx_forward_directional_visibility(lower + ivec2(1, 1), depth), weight.y);
    return mix(left, right, weight.x);
}

#ifdef LFX_TLV_DIRECTIONAL_LIGHT
float lfx_tlv_directional_static_visibility(ivec2 texel, ivec2 size, float receiver) {
    float blocker = texelFetch(LfxStaticDirectionalDepth, clamp(texel, ivec2(0), size - 1), 0).r;
    return blocker > 0.99 ? 1.0 : clamp((blocker - receiver) * 40.0 + 1.0, 0.0, 1.0);
}

float lfx_tlv_directional_static(vec3 position) {
    vec2 uv;
    float depth;
    if (!lfx_static_directional_coordinates(position, uv, depth)) return 1.0;
    ivec2 size = textureSize(LfxStaticDirectionalDepth, 0);
    vec2 texel = uv * vec2(size) - 0.5;
    ivec2 lower = ivec2(floor(texel));
    vec2 weight = fract(texel);
    float top = mix(lfx_tlv_directional_static_visibility(lower, size, depth),
            lfx_tlv_directional_static_visibility(lower + ivec2(1, 0), size, depth), weight.x);
    float bottom = mix(lfx_tlv_directional_static_visibility(lower + ivec2(0, 1), size, depth),
            lfx_tlv_directional_static_visibility(lower + ivec2(1, 1), size, depth), weight.x);
    return mix(top, bottom, weight.y);
}

vec2 lfx_tlv_directional_shadow(vec3 position, float voxelDepth, float lightingDepth) {
    int count = ForwardShadowSettings.x;
    float staticVisibility = lfx_tlv_directional_static(position);
    if (count == 0) return vec2(staticVisibility, 1.0);
    vec2 result = vec2(0.0);
    float distanceFade = clamp(lightingDepth * ForwardShadowFade.x + ForwardShadowFade.y, 0.0, 1.0);
    for (int index = 0; index < count; index++) {
        vec4 range = ForwardCascadeRange[index];
        float fromNear = voxelDepth - range.x;
        if (fromNear < 0.0) continue;
        float mask = clamp(fromNear / (range.z > 0.0 ? range.z : 0.01), 0.0, 1.0);
        if (index + 1 < count) {
            float fromFar = range.y - voxelDepth;
            if (fromFar < 0.0) continue;
            float fade = range.y - ForwardCascadeRange[index + 1].x;
            mask *= clamp(fromFar / (fade > 0.0 ? fade : 0.000001), 0.0, 1.0);
        }
        vec4 shadowPosition = ForwardWorldToShadow[index] * vec4(position, 1.0);
        vec2 uv = shadowPosition.xy / shadowPosition.w;
        vec4 bounds = ForwardShadowBounds[index];
        float visibility = 1.0;
        if (all(greaterThanEqual(uv, bounds.xy)) && all(lessThanEqual(uv, bounds.zw))) {
            float blocker = textureLod(LfxForwardDirectionalDepth, uv, 0.0).r;
            visibility = float(blocker == 0.0 || shadowPosition.z > blocker + range.w);
        }
        visibility = mix(visibility, 1.0, distanceFade * distanceFade);
        result += vec2(min(staticVisibility, visibility), 1.0) * mask;
    }
    return result;
}
#endif
