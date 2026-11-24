#ifndef LFX_POINT_SHADOW_QUALITY
#define LFX_POINT_SHADOW_QUALITY 5
#endif

const vec2 lfxShadowDisc5[5] = vec2[](
    vec2(0.000000, 2.500000), vec2(2.377641, 0.772542), vec2(1.469463, -2.022543),
    vec2(-1.469463, -2.022542), vec2(-2.377641, 0.772543));
const vec2 lfxShadowDisc12[12] = vec2[](
    vec2(0.000000, 2.500000), vec2(1.767767, 1.767767), vec2(2.500000, -0.000000),
    vec2(1.767767, -1.767767), vec2(-0.000000, -2.500000), vec2(-1.767767, -1.767767),
    vec2(-2.500000, 0.000000), vec2(-1.767766, 1.767768), vec2(-1.006119, -0.396207),
    vec2(1.000015, 0.427335), vec2(0.416807, -1.006577), vec2(-0.408872, 1.024430));
const vec2 lfxShadowDisc29[29] = vec2[](
    vec2(0.000000, 2.500000), vec2(1.016842, 2.283864), vec2(1.857862, 1.672826),
    vec2(2.377641, 0.772542), vec2(2.486305, -0.261321), vec2(2.165063, -1.250000),
    vec2(1.469463, -2.022543), vec2(0.519779, -2.445369), vec2(-0.519779, -2.445369),
    vec2(-1.469463, -2.022542), vec2(-2.165064, -1.250000), vec2(-2.486305, -0.261321),
    vec2(-2.377641, 0.772543), vec2(-1.857862, 1.672827), vec2(-1.016841, 2.283864),
    vec2(0.091021, -0.642186), vec2(0.698035, 0.100940), vec2(0.959731, -1.169393),
    vec2(-1.053880, 1.180380), vec2(-1.479156, -0.606937), vec2(-0.839488, -1.320002),
    vec2(1.438566, 0.705359), vec2(0.067064, -1.605197), vec2(0.728706, 1.344722),
    vec2(1.521424, -0.380184), vec2(-0.199515, 1.590091), vec2(-1.524323, 0.364010),
    vec2(-0.692694, -0.086749), vec2(-0.082476, 0.654088));

int lfx_point_shadow_cube_face(vec3 direction) {
    vec3 absoluteVector = abs(direction);
    float maximum = max(absoluteVector.x, max(absoluteVector.y, absoluteVector.z));
    if (maximum == absoluteVector.x) return absoluteVector.x == direction.x ? 0 : 1;
    if (maximum == absoluteVector.y) return absoluteVector.y == direction.y ? 2 : 3;
    return absoluteVector.z == direction.z ? 4 : 5;
}

vec3 lfx_point_shadow_cube_side(vec3 direction) {
    return direction.x == 0.0 && direction.y == 0.0 ? vec3(1.0, 0.0, 0.0)
            : normalize(vec3(direction.y, -direction.x, 0.0));
}

float lfx_point_shadow_pcf(samplerCubeShadow depths, mat4 viewProjection[6], float inverseResolution,
                           vec3 worldPosition, vec3 lightPosition, float inverseRadius, float depthBias, float slopeBias) {
    float shadow = 1.0;
    vec3 toLight = lightPosition - worldPosition;
    float distanceToLight = length(toLight);
    if (distanceToLight > 0.0 && distanceToLight * inverseRadius < 1.0) {
        vec3 direction = toLight / distanceToLight;
        vec3 side = lfx_point_shadow_cube_side(direction);
        vec3 up = cross(side, direction);
        side *= inverseResolution;
        up *= inverseResolution;
        int face = lfx_point_shadow_cube_face(toLight);
        vec4 projected = viewProjection[face] * vec4(-toLight, 1.0);
        float compareDepth = projected.z / projected.w;
        float bias = -(depthBias + slopeBias) / projected.w;
#if LFX_POINT_SHADOW_QUALITY <= 2
        shadow = texture(depths, vec4(toLight, compareDepth - bias));
#else
        shadow = 0.0;
#if LFX_POINT_SHADOW_QUALITY == 3
        const int count = 5;
#elif LFX_POINT_SHADOW_QUALITY == 4
        const int count = 12;
#else
        const int count = 29;
#endif
        for (int i = 0; i < count; ++i) {
#if LFX_POINT_SHADOW_QUALITY == 3
            vec2 disc = lfxShadowDisc5[i];
#elif LFX_POINT_SHADOW_QUALITY == 4
            vec2 disc = lfxShadowDisc12[i];
#else
            vec2 disc = lfxShadowDisc29[i];
#endif
            vec3 samplePosition = direction + side * disc.x + up * disc.y;
            shadow += texture(depths, vec4(samplePosition, compareDepth - bias * length(disc)));
        }
        shadow /= float(count);
#endif
    }
    return 1.0 - shadow;
}

float lfx_point_shadow_attenuation(float shadow, float sharpen, float fade) {
    shadow = clamp((shadow - 0.5) * sharpen + 0.5, 0.0, 1.0);
    shadow *= shadow;
    return sqrt(mix(1.0, shadow, fade));
}
