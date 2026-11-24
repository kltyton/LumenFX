#moj_import <lumenfx:lighting/subsurface_profile.glsl>

#ifdef LFX_SHADOW_LEGACY_TRANSMISSION
uniform sampler2D ShadowSubsurfaceData;
#endif

#ifndef LFX_SPOT_SHADOW
uniform samplerCube PointShadowRawDepth;

float lfx_point_subsurface_optical_mask(vec3 toLight, vec3 normal, float extinction, float normalScale, vec2 depthParameters,
                                         float inverseResolution, float inverseRadius, mat4 viewProjection[6]) {
    float distanceToLight = length(toLight);
    if (distanceToLight <= 0.0 || distanceToLight * inverseRadius >= 1.0) return 0.8;

    normalScale *= 0.5;
    vec3 direction = toLight / distanceToLight;
    vec3 side = lfx_point_shadow_cube_side(direction);
    vec3 up = cross(side, direction) * inverseResolution;
    side *= inverseResolution;
    int face = lfx_point_shadow_cube_face(direction);
    vec4 projected = viewProjection[face] * vec4(-toLight - normal * normalScale, 1.0);
    float sceneDepth = depthParameters.y / (projected.z / projected.w - depthParameters.x);
    float noL = clamp(dot(-direction, normal), 0.0, 1.0);
    float depthSum = 0.0;
    for (int i = 0; i < 5; ++i) {
        vec3 sampleDirection = direction + side * lfxShadowDisc5[i].x + up * lfxShadowDisc5[i].y;
        float sampleDepth = textureLod(PointShadowRawDepth, sampleDirection, 0.0).r;
        float shadowDepth = depthParameters.y / (sampleDepth - depthParameters.x);
        float optical = (sceneDepth - shadowDepth) * extinction * 10.0;
        float biased = optical > 0.0 ? optical + normalScale : max(0.0, optical * noL + normalScale);
        depthSum += clamp(abs(biased), 0.15, 5.0) + 0.25;
    }
    return clamp(1.0 - depthSum / 25.0, 0.0, 1.0);
}
#endif
