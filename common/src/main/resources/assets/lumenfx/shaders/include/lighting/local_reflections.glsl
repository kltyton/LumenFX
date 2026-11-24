layout(std140) uniform LfxLocalReflectionView {
    vec4 LocalReflectionSettings;
    vec4 LocalReflectionScaleAndLimit;
    vec4 LocalReflectionLuminance;
};
uniform samplerBuffer LfxLocalReflectionProxies;
uniform sampler2DArray LfxLocalReflectionFaces;
uniform sampler2D LfxLocalReflectionGF;

vec3 lfx_cube_face_vector(vec2 uv, int face) {
    if (face == 0) return vec3(1.0, -uv.y, -uv.x);
    if (face == 1) return vec3(-1.0, -uv.y, uv.x);
    if (face == 2) return vec3(uv.x, 1.0, uv.y);
    if (face == 3) return vec3(uv.x, -1.0, -uv.y);
    if (face == 4) return vec3(uv.x, -uv.y, 1.0);
    return vec3(-uv.x, -uv.y, -1.0);
}

vec2 lfx_cube_direction_to_face(vec3 direction, out int face) {
    vec3 m = abs(direction);
    vec2 coord;
    if (m.x >= max(m.y, m.z)) {
        face = direction.x >= 0.0 ? 0 : 1;
        coord = vec2(face == 0 ? -direction.z : direction.z, -direction.y) / max(m.x, 1e-20);
    } else if (m.y >= m.z) {
        face = direction.y >= 0.0 ? 2 : 3;
        coord = vec2(direction.x, face == 2 ? direction.z : -direction.z) / m.y;
    } else {
        face = direction.z >= 0.0 ? 4 : 5;
        coord = vec2(face == 4 ? direction.x : -direction.x, -direction.y) / m.z;
    }
    return coord * 0.5 + 0.5;
}

vec4 lfx_local_face_edge(ivec2 pixel, int face, int layer, int extent, int mip) {
    vec3 ray = lfx_cube_face_vector((vec2(pixel) + 0.5) * (2.0 / float(extent)) - 1.0, face);
    int neighbor;
    vec2 uv = lfx_cube_direction_to_face(ray, neighbor);
    ivec2 neighborPixel = clamp(ivec2(floor(uv * float(extent))), ivec2(0), ivec2(extent - 1));
    return texelFetch(LfxLocalReflectionFaces, ivec3(neighborPixel, layer + neighbor), mip);
}

vec4 lfx_local_face_tap(ivec2 pixel, int face, int layer, int extent, int mip) {
    bvec2 outside = bvec2(pixel.x < 0 || pixel.x >= extent, pixel.y < 0 || pixel.y >= extent);
    if (!any(outside)) return texelFetch(LfxLocalReflectionFaces, ivec3(pixel, layer + face), mip);
    if (!all(outside)) return lfx_local_face_edge(pixel, face, layer, extent, mip);
    ivec2 corner = clamp(pixel, ivec2(0), ivec2(extent - 1));
    return (texelFetch(LfxLocalReflectionFaces, ivec3(corner, layer + face), mip)
            + lfx_local_face_edge(ivec2(corner.x, pixel.y), face, layer, extent, mip)
            + lfx_local_face_edge(ivec2(pixel.x, corner.y), face, layer, extent, mip)) / 3.0;
}

vec4 lfx_local_cube_mip(vec2 uv, int face, int layer, int mip) {
    int extent = textureSize(LfxLocalReflectionFaces, mip).x;
    vec2 pixel = uv * float(extent) - 0.5;
    ivec2 low = ivec2(floor(pixel));
    if (all(greaterThanEqual(low, ivec2(0))) && all(lessThan(low + 1, ivec2(extent))))
        return textureLod(LfxLocalReflectionFaces, vec3(uv, float(layer + face)), float(mip));
    vec2 frac = fract(pixel);
    vec4 top = mix(lfx_local_face_tap(low, face, layer, extent, mip),
                   lfx_local_face_tap(low + ivec2(1, 0), face, layer, extent, mip), frac.x);
    vec4 bottom = mix(lfx_local_face_tap(low + ivec2(0, 1), face, layer, extent, mip),
                      lfx_local_face_tap(low + 1, face, layer, extent, mip), frac.x);
    return mix(top, bottom, frac.y);
}

vec4 lfx_local_cube_sample(vec3 ray, int layer, float mip) {
    int face;
    vec2 uv = lfx_cube_direction_to_face(ray, face);
    int low = int(floor(mip));
    vec4 first = lfx_local_cube_mip(uv, face, layer, low);
    float fraction = fract(mip);
    return fraction == 0.0 ? first : mix(first, lfx_local_cube_mip(uv, face, layer, low + 1), fraction);
}

float lfx_capture_box_far(float origin, float direction) {
    if (direction == 0.0) return abs(origin) <= 1.0 ? 1e20 : -1e20;
    return max((-1.0 - origin) / direction, (1.0 - origin) / direction);
}

vec3 lfx_local_capture_ray(vec3 ray, vec3 relative, float radius, vec3 offset, vec4 scales,
                           mat3 worldToBox, bool box, out float alpha) {
    if (box) {
        vec3 origin = worldToBox * relative;
        vec3 direction = worldToBox * ray;
        float farT = min(lfx_capture_box_far(origin.x, direction.x),
                min(lfx_capture_box_far(origin.y, direction.y), lfx_capture_box_far(origin.z, direction.z)));
        vec3 extents = scales.xyz - 0.5 * scales.w;
        vec3 p = origin * scales.xyz;
        float distanceToBox = length(max(-extents - p, vec3(0.0)) + max(p - extents, vec3(0.0)));
        alpha = scales.w > 0.0 ? 1.0 - smoothstep(0.0, 0.7 * scales.w, distanceToBox) : float(distanceToBox == 0.0);
        return relative + farT * ray - offset;
    }
    float b = dot(ray, relative);
    float discriminant = b * b - dot(relative, relative) + radius * radius;
    alpha = 0.0;
    if (discriminant < 0.0) return ray;
    float fade = clamp(2.5 * length(relative) / radius - 1.5, 0.0, 1.0);
    alpha = 1.0 - fade * fade * (3.0 - 2.0 * fade);
    return relative + (sqrt(discriminant) - b) * ray - offset;
}

struct LfxReflectionGather {
    vec3 radiance;
    float remaining;
    float averageBrightness;
    float remainingBrightness;
};

#if LFX_FORWARD_LOCAL_CAPTURES && LFX_FORWARD_BLEND_LOCAL_CAPTURES
uniform usamplerBuffer LfxForwardCaptureIndices;
#endif

LfxReflectionGather lfx_gather_local_reflections(vec3 position, vec3 ray, float roughness, float compositeAlpha, ivec2 selection) {
    LfxReflectionGather result = LfxReflectionGather(vec3(0.0), compositeAlpha, 0.0, 1.0);
    float mip = clamp(lfx_reflection_mip(roughness, LocalReflectionSettings.y), 0.0, LocalReflectionSettings.y - 1.0);
    for (int index = 0; index < selection.y && result.remaining >= 0.001; ++index) {
        int capture = index;
#if LFX_FORWARD_LOCAL_CAPTURES && LFX_FORWARD_BLEND_LOCAL_CAPTURES
        if (selection.x >= 0) capture = int(texelFetch(LfxForwardCaptureIndices, selection.x + index).r);
#endif
        int base = capture * 7;
        vec4 center = texelFetch(LfxLocalReflectionProxies, base);
        vec3 relative = position - center.xyz;
        if (dot(relative, relative) >= center.w * center.w) continue;
        vec4 offset = texelFetch(LfxLocalReflectionProxies, base + 1);
        vec4 properties = texelFetch(LfxLocalReflectionProxies, base + 6);
        vec4 scales = texelFetch(LfxLocalReflectionProxies, base + 2);
        mat3 transform = mat3(texelFetch(LfxLocalReflectionProxies, base + 3).xyz,
                              texelFetch(LfxLocalReflectionProxies, base + 4).xyz,
                              texelFetch(LfxLocalReflectionProxies, base + 5).xyz);
        float alpha;
        vec3 lookup = lfx_local_capture_ray(ray, relative, center.w, offset.xyz, scales, transform, properties.z > 0.0, alpha);
        alpha *= properties.w;
        if (alpha <= 0.0) continue;
        vec4 radiance = lfx_local_cube_sample(lookup, int(properties.y), mip);
        result.radiance += radiance.rgb * (alpha * properties.x * result.remaining);
        result.remaining *= 1.0 - radiance.a * alpha;
        result.averageBrightness += offset.w * (alpha * result.remainingBrightness);
        result.remainingBrightness *= 1.0 - alpha;
    }
    result.radiance *= LocalReflectionScaleAndLimit.rgb;
    result.averageBrightness *= dot(LocalReflectionScaleAndLimit.rgb, LocalReflectionLuminance.rgb);
    return result;
}

float lfx_local_reflection_mix(float roughness, float indirectBrightness, float averageBrightness) {
    float alpha = smoothstep(0.0, 1.0, clamp(roughness * LocalReflectionSettings.z + LocalReflectionSettings.w, 0.0, 1.0));
    float weight = min(indirectBrightness / max(averageBrightness, 0.0001), LocalReflectionScaleAndLimit.w);
    return mix(1.0, weight, alpha);
}

vec3 lfx_composite_local_reflections(vec3 position, vec3 ray, float roughness, float indirectBrightness,
                                     float compositeAlpha, vec3 skySample, float skyAverage, bool normalizeSky, ivec2 selection) {
    LfxReflectionGather result = lfx_gather_local_reflections(position, ray, roughness, compositeAlpha, selection);
    vec3 sky = result.remaining * skySample;
    if (normalizeSky) {
        result.radiance += sky;
        result.averageBrightness += result.remainingBrightness * skyAverage;
        sky = vec3(0.0);
    }
    return result.radiance * lfx_local_reflection_mix(roughness, indirectBrightness, result.averageBrightness) + sky;
}

vec3 lfx_composite_local_reflections(vec3 position, vec3 ray, float roughness, float indirectBrightness,
                                     float compositeAlpha, vec3 skySample, float skyAverage, bool normalizeSky) {
    return lfx_composite_local_reflections(position, ray, roughness, indirectBrightness, compositeAlpha,
            skySample, skyAverage, normalizeSky, ivec2(-1, int(LocalReflectionSettings.x)));
}

// Native non-HQ forward materials use the primitive's single selected capture. The source path does not
// parallax-correct this branch (APPROXIMATE_CONTINUOUS_SINGLE_CAPTURE_PARALLAX is 0).
vec3 lfx_composite_single_local_reflection(int capture, vec3 ray, float roughness, float indirectBrightness,
                                           vec3 skySample, bool normalizeSky) {
    vec3 captured = vec3(0.0);
    float remaining = 1.0;
    float averageBrightness = 0.0;
    if (capture >= 0 && capture < int(LocalReflectionSettings.x)) {
        int base = capture * 7;
        vec4 offset = texelFetch(LfxLocalReflectionProxies, base + 1);
        vec4 properties = texelFetch(LfxLocalReflectionProxies, base + 6);
        float mip = clamp(lfx_reflection_mip(roughness, LocalReflectionSettings.y), 0.0, LocalReflectionSettings.y - 1.0);
        vec4 radiance = lfx_local_cube_sample(ray, int(properties.y), mip);
        captured = radiance.rgb * properties.x * LocalReflectionScaleAndLimit.rgb;
        remaining -= radiance.a;
        averageBrightness = offset.w * dot(LocalReflectionScaleAndLimit.rgb, LocalReflectionLuminance.rgb);
    }
    if (normalizeSky) {
        return (captured + remaining * skySample) * lfx_local_reflection_mix(roughness, indirectBrightness, averageBrightness);
    }
    return captured * lfx_local_reflection_mix(roughness, indirectBrightness, averageBrightness) + remaining * skySample;
}
