#ifndef LFX_NIAGARA_VOLUME_INTEGRATE
#define LFX_NIAGARA_VOLUME_INTEGRATE

#ifndef LFX_VOLUME_COMPUTE
out vec4 fragColor;
#endif

#if LFX_VOLUME_HEIGHT_FOG && !LFX_VOLUME_CACHE_WRITE && !LFX_VOLUME_PRESHADE_WRITE
#moj_import <lumenfx:height_fog.glsl>
#if LFX_VOLUME_FROXEL_FOG
#define LFX_PIXEL_FOG 1
#moj_import <lumenfx:volume_fog.glsl>
#endif

vec4 lfx_heterogeneous_volume_fog(vec3 translatedPosition) {
    vec3 cameraRelative = translatedPosition.xzy * 0.01;
    vec4 height = vec4(0.0, 0.0, 0.0, 1.0);
#if LFX_VOLUME_FROXEL_FOG
    if (VolumeFactors.w > 0.5)
        height = lfx_height_fog(cameraRelative, lfx_volume_height_exclusion(cameraRelative));
    return lfx_combine_volume(height, cameraRelative);
#else
    return lfx_height_fog(cameraRelative, 0.0);
#endif
}
#endif

vec2 lfx_volume_intersection(vec3 origin, vec3 direction, float maximumT) {
    vec3 low = VolumeBoundsMin.xyz - origin;
    vec3 high = VolumeBoundsMax.xyz - origin;
    vec3 invDirection = 1.0 / direction;
    vec3 a = low * invDirection;
    vec3 b = high * invDirection;
    vec3 nearT = min(a, b);
    vec3 farT = max(a, b);
    for (int axis = 0; axis < 3; ++axis) {
        if (direction[axis] != 0.0) continue;
        if (low[axis] > 0.0 || high[axis] < 0.0) return vec2(1.0, 0.0);
        nearT[axis] = -1.0e30;
        farT[axis] = 1.0e30;
    }
    return vec2(max(0.0, max(nearT.x, max(nearT.y, nearT.z))),
                min(maximumT, min(farT.x, min(farT.y, farT.z))));
}

float lfx_volume_step(vec3 direction, float factor, float overrideSize) {
    vec3 voxel = (VolumeBoundsMax.xyz - VolumeBoundsMin.xyz) * 0.5 / VolumeResolution.xyz;
    vec3 distances = abs(voxel / direction);
    float stepSize = overrideSize > 0.0 ? overrideSize : min(distances.x, min(distances.y, distances.z)) * factor;
    stepSize = max(stepSize, 0.01);
    float diagonal = length(VolumeBoundsMax.xyz - VolumeBoundsMin.xyz);
    return uint(max(diagonal / stepSize, 1.0)) > uint(VolumeTrace.w) ? diagonal / VolumeTrace.w : stepSize;
}

float lfx_volume_jitter() {
    if (VolumePass.z < 0.5) return 0.0;
    ivec2 pixel = ivec2(gl_FragCoord.xy) & 127;
    int slice = int(VolumeResolution.w) & 7;
    return texelFetch(VolumeBlueNoiseSampler, ivec2(pixel.x, pixel.y + slice * 128), 0).r;
}

#if (defined(LFX_VOLUME_COMPUTE) && !LFX_VOLUME_CACHE_READ && !LFX_VOLUME_PRESHADE_WRITE) || LFX_VOLUME_LIGHT_PASS
#moj_import <lumenfx:lighting/point_light_area.glsl>
#moj_import <lumenfx:lighting/rect_light_geometry.glsl>

float lfx_volume_shadow_bias() {
    vec3 extent = VolumeBoundsMax.xyz - VolumeBoundsMin.xyz;
    vec3 cacheResolution = VolumeLightingGrid.xyz;
    return length(mat3(VolumeLocalToWorld) * (extent / cacheResolution)) * VolumeFactors.z;
}

vec3 lfx_volume_light_transmittance(vec3 world, vec3 lightDirection, float distanceToLight) {
    vec3 origin = (VolumeWorldToLocal * vec4(world, 1.0)).xyz;
    vec3 localDirection = mat3(VolumeWorldToLocal) * lightDirection;
    float localScale = length(localDirection);
    localDirection /= localScale;
    vec2 hit = lfx_volume_intersection(origin, localDirection, distanceToLight * localScale);
    if (hit.x >= hit.y) return vec3(1.0);
    float stepSize = lfx_volume_step(localDirection, VolumeFactors.y, VolumePass.w);
    int steps = max(int(ceil((hit.y - hit.x) / stepSize)), 1);
    vec3 transmittance = vec3(1.0);
    for (int index = 0; index < steps; ++index) {
        float start = hit.x + float(index) * stepSize;
        vec3 local = origin + (start + 0.5 * stepSize) * localDirection;
        vec3 point = (VolumeLocalToWorld * vec4(local, 1.0)).xyz;
        transmittance *= exp(-lfx_evaluate_volume(local, point).extinction * stepSize);
        if (all(lessThan(transmittance, vec3(1.0e-7)))) return vec3(0.0);
    }
    return transmittance;
}

#if LFX_VOLUME_DIRECTIONAL_SHADOW
float lfx_volume_directional_surface_shadow(vec3 world, float viewDepth) {
    vec3 sourceWorld = world.xzy * 0.01;
    float staticShadow = lfx_forward_directional_static(sourceWorld);
    if (ForwardShadowSettings.x == 0) return staticShadow;
    float dynamicShadow = lfx_forward_directional_shadow(sourceWorld, viewDepth);
    float fade = clamp(viewDepth * ForwardShadowFade.x + ForwardShadowFade.y, 0.0, 1.0);
    return mix(dynamicShadow, staticShadow, fade * fade);
}
#endif

vec3 lfx_volume_light(vec3 world) {
    bool directional = VolumeLightKind.x > 0.5;
    vec3 toLight = directional ? VolumeLightDirectionAndCone.xyz * VolumeTrace.y : VolumeLightPosition.xyz - world;
    float distanceSquared = dot(toLight, toLight);
    float radius = VolumeLightPosition.w;
    if (!directional && (distanceSquared >= radius * radius || radius <= 0.0)) return vec3(0.0);
    float distanceToLight = directional ? max(VolumeTrace.y, 0.0) : sqrt(distanceSquared);
    vec3 lightDirection = directional ? normalize(VolumeLightDirectionAndCone.xyz)
            : distanceToLight > 1.0e-6 ? toLight / distanceToLight : vec3(0.0, 0.0, 1.0);
    bool inverseSquared = VolumeLightShape.x > 0.5;
    float attenuation = directional ? 1.0
            : lfx_point_radial_mask(toLight, 1.0 / radius, VolumeLightShape.z, inverseSquared);
    if (!directional && VolumeRectShape.x > 0.0) {
        LfxRectLight rect = lfx_rect_light(toLight, VolumeLightDirectionAndCone.xyz, VolumeLightTangent.xyz, VolumeRectShape);
        float rectFalloff, rectNoL;
        lfx_rect_irradiance(rect, vec3(0.0), rectFalloff, rectNoL);
        attenuation *= rectFalloff;
        if (attenuation <= 0.0) return vec3(0.0);
    } else if (!directional) {
        LfxPointCapsule capsule = lfx_point_capsule(toLight, VolumeLightTangent.xyz, VolumeLightTangent.w,
                VolumeLightShape.y, VolumeLightShape.w);
        capsule.distanceBiasSquared = 0.0;
        attenuation *= lfx_integrate_point_light(capsule, inverseSquared);
    }
    if (!directional && VolumeLightDirectionAndCone.w >= 0.0) {
        attenuation *= distanceToLight > 1.0e-6
                ? lfx_spot_attenuation(lightDirection, VolumeLightDirectionAndCone.xyz,
                    vec2(VolumeLightDirectionAndCone.w, VolumeLightConeFalloff.x))
                : 0.0;
        if (attenuation <= 0.0) return vec3(0.0);
    }
    float surfaceShadow = 1.0;
#if LFX_VOLUME_DIRECTIONAL_SHADOW
    if (directional) {
        float viewDepth = max(dot(world, VolumeLightTangent.xyz), 0.0) * 0.01;
        surfaceShadow = lfx_volume_directional_surface_shadow(world, viewDepth);
    }
#endif
#if LFX_VOLUME_SURFACE_SHADOW
    if (!directional) {
#if LFX_VOLUME_SPOT_SHADOW
    if (distanceToLight > 1.0e-6)
        surfaceShadow = lfx_spot_shadow_pcf(VolumeSpotShadowDepth, VolumeShadowProjection[0], -toLight, VolumeShadowParameters.zw,
                VolumeShadowParameters.x, VolumeShadowParameters.y);
#else
    if (distanceToLight > 1.0e-6)
        surfaceShadow = lfx_point_shadow_pcf(VolumeShadowDepth, VolumeShadowProjection, VolumeShadowParameters.x,
                world, VolumeLightPosition.xyz, 1.0 / radius, VolumeShadowParameters.y, 0.0);
#endif
        surfaceShadow = mix(1.0, surfaceShadow, VolumeShadowStrength.x);
    }
#endif
    if (surfaceShadow <= 0.0) return vec3(0.0);
    vec3 radiance = VolumeLightColor.rgb * (attenuation * surfaceShadow);
    if (VolumeLightColor.w > 0.5 && distanceToLight > 1.0e-6 && any(greaterThan(radiance, vec3(0.0)))) {
        vec3 biasedPosition = world + lightDirection * lfx_volume_shadow_bias();
#if LFX_VOLUME_CACHE_TRANS_READ
        vec3 local = (VolumeWorldToLocal * vec4(biasedPosition, 1.0)).xyz;
        vec3 uvw = clamp((local - VolumeBoundsMin.xyz) / (VolumeBoundsMax.xyz - VolumeBoundsMin.xyz), 0.0, 1.0);
        radiance *= textureLod(VolumeLightingCache, uvw, 0.0).rgb;
#else
        radiance *= lfx_volume_light_transmittance(biasedPosition, lightDirection, distanceToLight);
#endif
    }
    return radiance;
}
#endif

#if LFX_VOLUME_PRESHADE_WRITE
#moj_import <lumenfx:niagara_volume_preshade.glsl>
#elif LFX_VOLUME_CACHE_WRITE
void main() {
    ivec3 voxel = ivec3(gl_GlobalInvocationID.xyz);
    if (any(greaterThanEqual(voxel, imageSize(VolumeCacheOutput)))) return;
    vec3 uvw = (vec3(voxel) + 0.5) / VolumeLightingGrid.xyz;
    vec3 local = mix(VolumeBoundsMin.xyz, VolumeBoundsMax.xyz, uvw);
    vec3 world = (VolumeLocalToWorld * vec4(local, 1.0)).xyz;
    LfxVolumeMaterial material = lfx_evaluate_volume(local, world);
    if (all(lessThan(material.extinction, vec3(1.0e-7)))) {
#if LFX_VOLUME_CACHE_TRANS_WRITE
        imageStore(VolumeCacheOutput, voxel, vec4(1.0));
#else
        imageStore(VolumeCacheOutput, voxel, vec4(0.0));
#endif
        return;
    }
#if LFX_VOLUME_CACHE_TRANS_WRITE
    bool directional = VolumeLightKind.x > 0.5;
    vec3 toLight = directional ? VolumeLightDirectionAndCone.xyz : VolumeLightPosition.xyz - world;
    float distanceToLight = directional ? max(VolumeTrace.y, 0.0) : length(toLight);
    vec3 transmittance = vec3(1.0);
    if (distanceToLight > 1.0e-6)
        transmittance = lfx_volume_light_transmittance(world,
                directional ? normalize(toLight) : toLight / distanceToLight, distanceToLight);
    imageStore(VolumeCacheOutput, voxel, vec4(transmittance, 1.0));
#else
    vec3 previous = VolumePass.x > 1.5 ? imageLoad(VolumeCacheOutput, voxel).rgb : vec3(0.0);
    imageStore(VolumeCacheOutput, voxel, vec4(previous + lfx_volume_light(world) * VolumeLightingGrid.w, 0.0));
#endif
}
#else
struct LfxVolumeRay {
    vec3 worldOrigin;
    vec3 worldDirection;
    vec3 localOrigin;
    vec3 localDirection;
    float localScale;
    vec2 hit;
};

LfxVolumeRay lfx_volume_view_ray() {
    vec2 uv = lfx_depth_viewport_uv();
    float depth = texelFetch(VolumeSceneDepth, ivec2(gl_FragCoord.xy), 0).r;
    vec4 nearView = lfx_volume_homogeneous_view(uv, 0.0);
    vec4 endView = lfx_volume_homogeneous_view(uv, depth);
    vec4 rayView = lfx_volume_homogeneous_view(uv, 0.5);
    vec3 viewOrigin = VolumePass.y > 0.5 ? nearView.xyz / nearView.w : vec3(0.0);
    vec3 viewDirection = normalize(rayView.xyz / rayView.w - viewOrigin);
    float endT = abs(endView.w) < 1.0e-8 ? VolumeTrace.x : min(VolumeTrace.x,
            100.0 * dot(endView.xyz / endView.w - viewOrigin, viewDirection));
    vec3 worldOrigin = (VolumeViewToWorld * vec4(viewOrigin, 1.0)).xyz.xzy * 100.0;
    vec3 worldDirection = normalize((mat3(VolumeViewToWorld) * viewDirection).xzy);
    vec3 localOrigin = (VolumeWorldToLocal * vec4(worldOrigin, 1.0)).xyz;
    vec3 localDirection = mat3(VolumeWorldToLocal) * worldDirection;
    float localScale = length(localDirection);
    localDirection /= localScale;
    vec2 hit = lfx_volume_intersection(localOrigin, localDirection, endT * localScale);
    return LfxVolumeRay(worldOrigin, worldDirection, localOrigin, localDirection, localScale, hit);
}

float lfx_volume_opacity(vec3 transmittance) {
    return 1.0 - dot(transmittance, vec3(0.2126390059, 0.7151686788, 0.0721923154));
}

vec4 lfx_integrate_volume() {
    LfxVolumeRay ray = lfx_volume_view_ray();
    vec2 hit = ray.hit;
    if (hit.x >= hit.y) return vec4(0.0);

    float jitter = lfx_volume_jitter();
    float stepSize = lfx_volume_step(ray.localDirection, VolumeFactors.x, VolumeTrace.z);
    float jitterSize = stepSize * jitter;
    int steps = max(int(ceil((hit.y - hit.x) / stepSize)), 1);
    vec3 transmittance = vec3(1.0);
    vec3 radiance = vec3(0.0);
#if LFX_VOLUME_HEIGHT_FOG
    vec4 fogAtEntry = lfx_heterogeneous_volume_fog(ray.worldOrigin + hit.x / ray.localScale * ray.worldDirection);
    vec4 fogAtExit = lfx_heterogeneous_volume_fog(ray.worldOrigin + hit.y / ray.localScale * ray.worldDirection);
    vec3 previousFog = vec3(0.0);
    vec3 fogRadiance = vec3(0.0);
#endif
    for (int index = 0; index < steps; ++index) {
        float t = hit.x + stepSize * (jitter + float(index));
        // Jitter moves the sample within each step without changing the integration interval.
        float distance = index == steps - 1 ? hit.y - (t - jitterSize) : stepSize;
        vec3 local = ray.localOrigin + t * ray.localDirection;
        vec3 world = ray.worldOrigin + (t / ray.localScale) * ray.worldDirection;
        LfxVolumeMaterial material = lfx_evaluate_volume(local, world);
        vec3 direct = vec3(0.0);
#if LFX_VOLUME_CACHE_READ
        vec3 uvw = clamp((local - VolumeBoundsMin.xyz) / (VolumeBoundsMax.xyz - VolumeBoundsMin.xyz), 0.0, 1.0);
        vec3 lighting = textureLod(VolumeLightingCache, uvw, 0.0).rgb / VolumeLightingGrid.w;
        vec3 scattering = material.albedo * material.extinction;
        radiance += material.emission * transmittance * distance;
        direct = lighting * scattering * (1.0 / 12.566370614359172) * transmittance * distance;
#elif defined(LFX_VOLUME_COMPUTE) || LFX_VOLUME_LIGHT_PASS
#ifdef LFX_VOLUME_COMPUTE
        if (VolumePass.x > 0.5) {
#endif
        vec3 scattering = material.albedo * material.extinction;
        if (any(greaterThan(scattering, vec3(0.0))))
            direct = lfx_volume_light(world) * scattering * (1.0 / 12.566370614359172) * transmittance * distance;
#ifdef LFX_VOLUME_COMPUTE
        } else radiance += material.emission * transmittance * distance;
#endif
#else
        radiance += material.emission * transmittance * distance;
#endif
#if LFX_VOLUME_HEIGHT_FOG
        if (any(greaterThan(material.extinction, vec3(0.0)))) {
            vec4 fog = mix(fogAtEntry, fogAtExit, clamp((t - hit.x) / (hit.y - hit.x), 0.0, 1.0));
            direct *= fog.a;
            fogRadiance += (fog.rgb - previousFog) * clamp(transmittance, 0.0, 1.0);
            previousFog = fog.rgb;
        }
#endif
        radiance += direct;
        transmittance *= exp(-material.extinction * distance);
        if (all(lessThan(transmittance, vec3(1.0e-7)))) {
            transmittance = vec3(0.0);
            break;
        }
    }
#ifdef LFX_VOLUME_COMPUTE
    if (VolumePass.x > 0.5) return vec4(radiance, 0.0);
#elif LFX_VOLUME_LIGHT_PASS
    return vec4(radiance, 0.0);
#endif
#if LFX_VOLUME_HEIGHT_FOG
    radiance += fogRadiance * clamp(1.0 - transmittance, 0.0, 1.0);
#endif
    return vec4(radiance, lfx_volume_opacity(transmittance));
}

void main() {
#ifdef LFX_VOLUME_COMPUTE
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(pixel, imageSize(VolumeResult)))) return;
    imageStore(VolumeResult, pixel, lfx_integrate_volume());
#else
    fragColor = lfx_integrate_volume();
#endif
}

#endif // LFX_VOLUME_CACHE_WRITE

#endif
