#ifndef LFX_ATMOSPHERE_AERIAL_SAMPLING
#define LFX_ATMOSPHERE_AERIAL_SAMPLING

vec4 lfx_sample_aerial_perspective(sampler3D volume, mat4 worldToClip, mat4 clipToWorld, vec4 depth,
                                  vec4 drawOrigin, vec4 camera, vec3 position, vec3 surface) {
    if (depth.y <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 toPosition = position + drawOrigin.xyz - camera.xyz;
    vec2 screenUv;
    if (depth.w >= 1.5) {
        float distanceSquared = dot(toPosition, toPosition);
        if (distanceSquared == 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
        vec3 direction = toPosition.xzy * inversesqrt(distanceSquared);
        const float twoPi = 6.283185307179586;
        // The capture LUT uses world longitude and sine of elevation.
        float longitude = 0.0;
        float horizontal = length(direction.xy);
        if (horizontal > 0.0) {
            longitude = acos(clamp(direction.x / horizontal, -1.0, 1.0));
            if (direction.y < 0.0) longitude = twoPi - longitude;
        }
        vec2 unitUv = vec2(longitude / twoPi, direction.z * 0.5 + 0.5);
        vec2 extent = vec2(textureSize(volume, 0).xy);
        screenUv = (unitUv * extent + 0.5) / (extent + 1.0);
    } else {
        vec4 clip = worldToClip * vec4(surface + drawOrigin.xyz, 1.0);
        if (clip.w == 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
        vec2 ndc = clip.xy / clip.w;
        screenUv = ndc * vec2(0.5, -0.5) + 0.5;
        if (depth.w > 0.0) {
            vec4 nearPoint = clipToWorld * vec4(ndc, 1.0, 1.0);
            vec3 orthoCamera = nearPoint.xyz / nearPoint.w + vec3(0.0, drawOrigin.w, 0.0);
            toPosition += orthoCamera + toPosition;
        }
    }
    float distanceKm = max(0.0, length(toPosition) * 0.001 - depth.x);
    float volumeW = sqrt(distanceKm * depth.y);
    float slice = volumeW * depth.z;
    float weight = slice < 0.7071067811865475 ? clamp(2.0 * slice * slice, 0.0, 1.0) : 1.0;
    weight *= clamp(distanceKm * 100000.0, 0.0, 1.0);
    vec4 aerial = textureLod(volume, vec3(screenUv, volumeW), 0.0);
    aerial.rgb *= camera.w == 0.0 ? weight : 0.0;
    aerial.a = 1.0 - weight * (1.0 - aerial.a);
    return aerial;
}

#endif
