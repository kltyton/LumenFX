#moj_import <lumenfx:lighting/point_light_area.glsl>
#moj_import <lumenfx:lighting/rect_light_geometry.glsl>
#moj_import <lumenfx:lighting/rect_light_source.glsl>
#moj_import <lumenfx:lighting/volume_local_shadow.glsl>

float lfx_fog_rect_fade(LfxRectLight rect, vec3 toLight, float softDistance) {
    vec3 local = transpose(rect.axes) * toLight;
    float fade = clamp(local.z / softDistance, 0.0, 1.0);
    float cosine = FogLocalRect.z;
    float barnDepth = FogLocalRect.w * cosine;
    float tipWidth = 4.0 * softDistance;
    if (local.z < barnDepth + tipWidth) {
        float tip = clamp((local.z - barnDepth) / tipWidth, 0.0, 1.0);
        tip = mix(tip, 1.0, 1.0 - clamp(FogLocalRect.w / softDistance, 0.0, 1.0));
        vec2 plane = cosine * FogLocalRect.xy + sqrt(1.0 - cosine * cosine) * local.z;
        vec2 nearSide = clamp((plane - cosine * local.xy) / softDistance, 0.0, 1.0);
        vec2 farSide = clamp((plane + cosine * local.xy) / softDistance, 0.0, 1.0);
        fade *= mix(nearSide.x * nearSide.y * farSide.x * farSide.y, 1.0, tip);
    }
    return fade;
}

vec3 lfx_fog_local_radiance(vec3 position, vec3 toLight, vec3 direction, float inverseRadius, float distanceBias, float softDistance) {
    bool inverseSquared = FogLocalShape.w > 0.5;
    float mask = lfx_point_radial_mask(toLight, inverseRadius, FogLocalShape.z, inverseSquared);
    vec3 color = FogLocalColor.rgb;
    float lighting;
    if (FogLocalRect.x > 0.0) {
        LfxRectLight rect = lfx_rect_light(toLight, FogLocalDirection.xyz, FogLocalTangent.xyz, FogLocalRect);
        float noL;
        lfx_rect_irradiance(rect, vec3(0.0), lighting, noL);
        if (lighting <= 0.0) return vec3(0.0);
        if (softDistance > 0.0) lighting *= lfx_fog_rect_fade(rect, toLight, softDistance);
        color *= lfx_rect_source_radiance(rect, vec3(lighting), FogLocalRectUv);
    } else {
        float cosine = FogLocalDirection.w;
        if (cosine >= 0.0) {
            mask *= lfx_spot_attenuation(direction, FogLocalDirection.xyz, vec2(cosine, FogLocalCone.x));
            if (softDistance > 0.0 && cosine > 0.0) {
                float projected = dot(FogLocalDirection.xyz, toLight);
                float aperture = projected * sqrt(1.0 - cosine * cosine) / cosine;
                float separation = length(toLight - FogLocalDirection.xyz * projected);
                mask *= clamp((aperture - separation) / softDistance, 0.0, 1.0);
            }
        }
        LfxPointCapsule capsule = lfx_point_capsule(toLight, FogLocalTangent.xyz, FogLocalTangent.w,
                FogLocalShape.x, FogLocalShape.y);
        capsule.distanceBiasSquared = distanceBias * distanceBias;
        lighting = lfx_integrate_point_light(capsule, inverseSquared);
    }
    float attenuation = mask * lighting;
    if (attenuation <= 0.0) return vec3(0.0);
    attenuation *= lfx_volume_local_shadow(toLight, inverseRadius);
#ifdef LFX_LOCAL_LIGHT_FUNCTION
    color *= lfx_local_light_function(position, length(position));
#endif
    return color * attenuation;
}
