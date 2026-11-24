#version 330
#moj_import <lumenfx:volumetric_common.glsl>
#moj_import <lumenfx:fog_material_position.glsl>
#moj_import <lumenfx:fog_particle_light_uniforms.glsl>
#moj_import <lumenfx:fog_light_sampling.glsl>
#moj_import <lumenfx:fog_history_reprojection.glsl>
#ifdef LFX_LOCAL_LIGHT_FUNCTION
#moj_import <lumenfx:lighting/light_function_atlas.glsl>
#endif
#ifdef LFX_FOG_LOCAL_LIGHT
#moj_import <lumenfx:fog_local_light.glsl>
#endif
#ifdef LFX_FOG_VOLUMETRIC_LIGHTMAP
#moj_import <lumenfx:lighting/fog_volumetric_lightmap.glsl>
#endif
#ifdef LFX_FOG_DIRECTIONAL_LIGHT
#moj_import <lumenfx:lighting/forward_directional_shadow.glsl>
#ifdef LFX_FOG_PROJECTED_FUNCTION
uniform sampler2D FogDirectionalFunction;
#endif
#endif

uniform sampler2D MediumSampler;
flat in int fogLightIndex;
flat in int fogLightSlice;
out vec4 fragColor;

float lfx_fog_phase(float g, float cosine) {
    float denominator = 1.0 + g * g + 2.0 * g * cosine;
    return (1.0 - g * g) / (12.566370614359172 * denominator * sqrt(denominator));
}

void main() {
    ivec3 cell;
    if (!lfx_atlas_cell(cell) || cell.z != fogLightSlice) discard;
    vec3 historyUV;
    float historyAlpha;
    if (!lfx_reproject_fog_history(cell, historyUV, historyAlpha)) discard;
    vec3 coefficient = texelFetch(MediumSampler, ivec2(gl_FragCoord.xy), 0).rgb;
    if (all(equal(coefficient, vec3(0.0)))) discard;
    int samples = FogParticleLightExposure.z > 0.5 && historyAlpha < 0.001
            ? FogParticleLightCount.z : 1;
#if !defined(LFX_FOG_DIRECTIONAL_LIGHT) && !defined(LFX_FOG_INDIRECT_LIGHT)
#ifdef LFX_FOG_LOCAL_LIGHT
    vec4 centre = FogLocalCentre;
    float exposureBlend = FogLocalColor.w;
#else
    vec4 centre = texelFetch(ParticleLights, fogLightIndex * 3);
    vec4 colorExponent = texelFetch(ParticleLights, fogLightIndex * 3 + 1);
    vec4 scales = texelFetch(ParticleLights, fogLightIndex * 3 + 2);
    float exposureBlend = scales.w;
#endif
    float exposure = FogParticleLightExposure.x <= 0.0 ? 1.0
            : exp(-exposureBlend * log(FogParticleLightExposure.x));
    float inverseRadius = 1.0 / max(centre.w, 0.0001);
#endif
    vec3 scattering = vec3(0.0);
    for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
        vec3 offset = lfx_fog_light_sample_offset(cell, sampleIndex);
        float slice;
        vec3 position = lfx_material_cell_world(cell, offset, slice);
        float cameraLengthSquared = dot(position, position);
        vec3 cameraVector = cameraLengthSquared > 0.0 ? position * inversesqrt(cameraLengthSquared) : vec3(0.0);
#ifdef LFX_FOG_DIRECTIONAL_LIGHT
        vec3 directional = FogDirectionalDirection.xyz;
        float phase = lfx_fog_phase(FogAlbedoPhase.w, dot(directional.xzy, -cameraVector));
        float visibility = lfx_forward_directional_static(position)
                * lfx_forward_directional_shadow(position, lfx_depth_from_slice(slice) * 0.01);
        vec3 radiance = FogDirectionalColor.rgb;
#ifdef LFX_LOCAL_LIGHT_FUNCTION
        vec3 sourcePosition = position.xzy * 100.0;
        radiance *= lfx_local_light_function(sourcePosition, length(sourcePosition));
#elif defined(LFX_FOG_PROJECTED_FUNCTION)
        vec4 functionPosition = FogDirectionalFunctionMatrix * vec4(position, 1.0);
        radiance *= textureLod(FogDirectionalFunction, functionPosition.xy * 0.5 + 0.5, 0.0).r;
#endif
        scattering += radiance * (phase * visibility);
#elif defined(LFX_FOG_INDIRECT_LIGHT)
        vec3 sourceDirection = cameraVector.xzy;
        vec4 normal = vec4(sourceDirection * -FogAlbedoPhase.w, 1.0);
        vec3 sky = FogSkyHeightColorAndOverride.w > 0.0
                ? FogSkyHeightColorAndOverride.rgb * 0.28209479177387814
                : FogSkyColorAndScattering.rgb * max(vec3(0.0), vec3(dot(FogSkyIrradiance[0], normal),
                    dot(FogSkyIrradiance[1], normal), dot(FogSkyIrradiance[2], normal)));
        float visibility = FogSkyFlags.x == 0 ? 1.0 : sqrt(3.0);
#ifdef LFX_FOG_VOLUMETRIC_LIGHTMAP
        vec3 brick;
        if (lfx_vlm_coordinate(position.xzy, brick)) {
            if (FogSkyFlags.x != 0 && FogSkyFlags.y != 0)
                visibility = length(lfx_vlm_sample(LfxVlmSkyBent, brick).rgb * 2.0 - 1.0);
            if (FogVlmOffsetAndScattering.w > 0.0)
                scattering += lfx_fog_vlm_scattering(brick, sourceDirection, FogAlbedoPhase.w);
        }
#endif
        scattering += max(sky, vec3(0.0)) * (FogSkyColorAndScattering.w * visibility);
#else
        vec3 nextCell = lfx_cell_world_at_slice(vec2(cell.xy) + offset.xy + 1.0, slice + 1.0);
        float cellRadiusCm = length(nextCell - position) * 100.0;
        float distanceBias = max(cellRadiusCm * FogMaterial.z, 1.0);
        vec3 toLight = (centre.xyz - position) * 100.0;
        float squaredDistance = dot(toLight, toLight);
        float normalizedSquared = squaredDistance * inverseRadius * inverseRadius;
        if (normalizedSquared >= 1.0) continue;
        vec3 direction = squaredDistance > 0.0 ? toLight * inversesqrt(squaredDistance) : vec3(0.0);
        float phase = lfx_fog_phase(FogAlbedoPhase.w, dot(direction, -cameraVector));
#ifdef LFX_FOG_LOCAL_LIGHT
        float softDistance = 0.0;
        if (FogParticleLightExposure.w > 0.0) {
            vec3 adjacent = lfx_cell_world_at_slice(vec2(cell.xy) + offset.xy + 1.0, slice);
            softDistance = length(adjacent - position) * 100.0 * FogParticleLightExposure.w;
        }
        scattering += lfx_fog_local_radiance(position.xzy * 100.0, toLight.xzy, direction.xzy,
                inverseRadius, distanceBias, softDistance)
                * (phase * exposure);
#else
        float attenuation;
        if (colorExponent.w == 0.0) {
            float mask = clamp(1.0 - normalizedSquared * normalizedSquared, 0.0, 1.0);
            attenuation = mask * mask / (squaredDistance + distanceBias * distanceBias);
        } else attenuation = pow(1.0 - clamp(normalizedSquared, 0.0, 1.0), colorExponent.w);
        scattering += colorExponent.rgb * (phase * attenuation * scales.z * exposure);
#endif
#endif
    }
    fragColor = vec4(scattering * coefficient / float(samples), 0.0);
}
