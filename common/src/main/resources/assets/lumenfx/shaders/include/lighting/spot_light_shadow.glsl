#ifndef LFX_SPOT_SHADOW_QUALITY
#define LFX_SPOT_SHADOW_QUALITY 2
#endif

vec2 lfx_spot_shadow_uv(vec4 clip) {
    return clip.xy / clip.w * vec2(0.5, -0.5) + 0.5;
}

float lfx_spot_shadow_depth(float lightDistance, vec2 perspective) {
    float nearPlane = perspective.y / (1.0 - perspective.x);
    return 1.0 - (perspective.x * lightDistance + perspective.y) / nearPlane;
}

float lfx_spot_shadow_sample(sampler2D depth, vec2 uv) {
    return 1.0 - textureLod(depth, uv, 0.0).r;
}

float lfx_spot_shadow_weight(float caster, float receiver, float scale, bool transmission) {
    if (!transmission) return clamp((caster - receiver) * scale + 1.0, 0.0, 1.0);
    if (caster > 0.99) return 1.0;
    return exp2(-1.442695 * max(receiver - caster, 0.0) * scale);
}

float lfx_spot_shadow_pcf(sampler2D depth, mat4 projection, vec3 relativePosition,
                          vec2 perspective, float inverseResolution, float scale, bool transmission, float bias) {
    vec4 clip = projection * vec4(relativePosition, 1.0);
    if (clip.w <= 0.0 || (!transmission && clip.z > clip.w)) return 1.0;
    vec2 uv = lfx_spot_shadow_uv(clip);
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return 1.0;
    float receiver = lfx_spot_shadow_depth(clip.w, perspective);
    receiver = transmission ? receiver + bias : min(receiver, 0.99999);
#if LFX_SPOT_SHADOW_QUALITY == 1
    return lfx_spot_shadow_weight(lfx_spot_shadow_sample(depth, uv), receiver, scale, transmission);
#else
#if LFX_SPOT_SHADOW_QUALITY == 2
    const int taps = 1;
#elif LFX_SPOT_SHADOW_QUALITY == 3
    const int taps = 3;
#else
    const int taps = 5;
#endif
    vec2 texel = uv / inverseResolution - 0.5;
    vec2 fraction = fract(texel);
    vec2 center = (floor(texel) + 0.5 - float(taps / 2)) * inverseResolution;
    float result = 0.0;
    for (int y = 0; y <= taps; ++y) {
        float wy = y == 0 ? 1.0 - fraction.y : y == taps ? fraction.y : 1.0;
        for (int x = 0; x <= taps; ++x) {
            float wx = x == 0 ? 1.0 - fraction.x : x == taps ? fraction.x : 1.0;
            float caster = lfx_spot_shadow_sample(depth, center + vec2(x, y) * inverseResolution);
            result += wx * wy * lfx_spot_shadow_weight(caster, receiver, scale, transmission);
        }
    }
    return result / float(taps * taps);
#endif
}

float lfx_spot_shadow_pcf(sampler2D depth, mat4 projection, vec3 relativePosition,
                          vec2 perspective, float inverseResolution, float transition) {
    return lfx_spot_shadow_pcf(depth, projection, relativePosition, perspective, inverseResolution, transition, false, 0.0);
}
