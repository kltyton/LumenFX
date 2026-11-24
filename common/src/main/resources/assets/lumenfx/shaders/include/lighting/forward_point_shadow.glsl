#ifndef LFX_FORWARD_POINT_SHADOW_GLSL
#define LFX_FORWARD_POINT_SHADOW_GLSL

#moj_import <lumenfx:lighting/point_light_shadow.glsl>

uniform sampler2D LfxForwardPointShadowAtlas;
uniform samplerBuffer LfxForwardPointShadowData;

const int LFX_FORWARD_POINT_SHADOW_SLOT_TEXELS = 56;
const int LFX_FORWARD_POINT_SHADOW_SETTINGS = 6;
const int LFX_FORWARD_POINT_SHADOW_FADE_QUALITY = 7;
const int LFX_FORWARD_POINT_SHADOW_MATRICES = 8;
const int LFX_FORWARD_POINT_SHADOW_INVERSE_MATRICES = 32;

mat4 lfx_forward_point_shadow_matrix(int base, int face, bool inverseMatrix) {
    int matrixBase = base + (inverseMatrix ? LFX_FORWARD_POINT_SHADOW_INVERSE_MATRICES
            : LFX_FORWARD_POINT_SHADOW_MATRICES) + face * 4;
    return mat4(texelFetch(LfxForwardPointShadowData, matrixBase),
            texelFetch(LfxForwardPointShadowData, matrixBase + 1),
            texelFetch(LfxForwardPointShadowData, matrixBase + 2),
            texelFetch(LfxForwardPointShadowData, matrixBase + 3));
}

vec2 lfx_forward_point_shadow_face_uv(int base, int face, vec3 direction) {
    vec4 projected = lfx_forward_point_shadow_matrix(base, face, false) * vec4(-direction, 1.0);
    return projected.xy / projected.w * vec2(0.5, -0.5) + 0.5;
}

float lfx_forward_point_shadow_compare_tap(int base, int face, ivec2 texel,
                                           float compareDepth, int faceSize) {
    int faceBase = base + face;
    ivec2 origin = ivec2(texelFetch(LfxForwardPointShadowData, faceBase).xy);
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, ivec2(faceSize)))) {
        vec2 ndc = ((vec2(texel) + 0.5) / float(faceSize) - 0.5) * vec2(2.0, -2.0);
        vec4 rayPoint = lfx_forward_point_shadow_matrix(base, face, true) * vec4(ndc, 1.0, 1.0);
        vec3 ray = rayPoint.xyz / rayPoint.w;
        face = lfx_point_shadow_cube_face(-ray);
        vec2 remappedUv = lfx_forward_point_shadow_face_uv(base, face, -ray);
        texel = clamp(ivec2(floor(remappedUv * float(faceSize))), ivec2(0), ivec2(faceSize - 1));
        origin = ivec2(texelFetch(LfxForwardPointShadowData, base + face).xy);
    }
    float depth = texelFetch(LfxForwardPointShadowAtlas, origin + texel, 0).r;
    return compareDepth < depth ? 1.0 : 0.0;
}

float lfx_forward_point_shadow_bilinear(int base, int face, vec3 direction, float compareDepth) {
    vec4 rectangle = texelFetch(LfxForwardPointShadowData, base + face);
    int faceSize = int(rectangle.z);
    vec2 texelPosition = lfx_forward_point_shadow_face_uv(base, face, direction) * float(faceSize) - 0.5;
    ivec2 first = ivec2(floor(texelPosition));
    vec2 fraction = fract(texelPosition);
    float visibility = 0.0;
    for (int y = 0; y < 2; ++y) {
        float weightY = y == 0 ? 1.0 - fraction.y : fraction.y;
        for (int x = 0; x < 2; ++x) {
            float weightX = x == 0 ? 1.0 - fraction.x : fraction.x;
            visibility += weightX * weightY * lfx_forward_point_shadow_compare_tap(
                    base, face, first + ivec2(x, y), compareDepth, faceSize);
        }
    }
    return visibility;
}

float lfx_forward_point_shadow(int shadowSlot, vec3 sourcePosition, vec3 lightPosition,
                               float inverseRadius, float normalDotLight) {
    if (shadowSlot < 0) return 1.0;
    int base = shadowSlot * LFX_FORWARD_POINT_SHADOW_SLOT_TEXELS;
    vec4 settings = texelFetch(LfxForwardPointShadowData, base + LFX_FORWARD_POINT_SHADOW_SETTINGS);
    vec4 fadeQuality = texelFetch(LfxForwardPointShadowData, base + LFX_FORWARD_POINT_SHADOW_FADE_QUALITY);
    vec3 toLight = lightPosition - sourcePosition;
    float distanceToLight = length(toLight);
    if (!(distanceToLight > 0.0) || distanceToLight * inverseRadius >= 1.0) return 1.0;

    vec3 direction = toLight / distanceToLight;
    int face = lfx_point_shadow_cube_face(toLight);
    vec4 projected = lfx_forward_point_shadow_matrix(base, face, false) * vec4(-toLight, 1.0);
    float compareDepth = projected.z / projected.w;
    float slopeBias = (1.0 - normalDotLight) * settings.y;
    float bias = -(settings.x + slopeBias) / projected.w;
    vec3 side = lfx_point_shadow_cube_side(direction);
    vec3 up = cross(side, direction);
    float inverseResolution = fadeQuality.z;
    side *= inverseResolution;
    up *= inverseResolution;

    float shadow = 0.0;
    int quality = int(fadeQuality.y);
    if (quality <= 2) {
        shadow = 1.0 - lfx_forward_point_shadow_bilinear(base, face, direction, compareDepth - bias);
    } else {
        int count = quality == 3 ? 5 : quality == 4 ? 12 : 29;
        for (int index = 0; index < count; ++index) {
            vec2 disc;
            if (quality == 3) disc = lfxShadowDisc5[index];
            else if (quality == 4) disc = lfxShadowDisc12[index];
            else disc = lfxShadowDisc29[index];
            vec3 sampleDirection = direction + side * disc.x + up * disc.y;
            int sampleFace = lfx_point_shadow_cube_face(sampleDirection);
            float sampleVisibility = lfx_forward_point_shadow_bilinear(base, sampleFace, sampleDirection,
                    compareDepth - bias * length(disc));
            shadow += 1.0 - sampleVisibility;
        }
        shadow /= float(count);
    }
    return lfx_point_shadow_attenuation(shadow, settings.w, fadeQuality.x);
}

#endif
