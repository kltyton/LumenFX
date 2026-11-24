#version 330
#moj_import <lumenfx:lighting/translucency_volume_grid.glsl>
#moj_import <lumenfx:lighting/point_light_area.glsl>

#if defined(LFX_TLV_DIRECTIONAL_LIGHT)
#moj_import <lumenfx:lighting/translucency_volume_directional_light.glsl>
#moj_import <lumenfx:lighting/forward_directional_shadow.glsl>
#ifdef LFX_TLV_MATERIAL_FUNCTION
uniform sampler2D TlvMaterialFunction;
#endif
#elif defined(LFX_TLV_LOCAL_LIGHT)
#moj_import <lumenfx:lighting/translucency_volume_local_light.glsl>
#moj_import <lumenfx:lighting/rect_light_geometry.glsl>
#moj_import <lumenfx:lighting/volume_local_shadow.glsl>
#ifdef LFX_LOCAL_LIGHT_FUNCTION
#moj_import <lumenfx:lighting/light_function_atlas.glsl>
#elif defined(LFX_TLV_MATERIAL_FUNCTION)
uniform sampler2D TlvMaterialFunction;
#endif
#else
uniform samplerBuffer ParticleLights;
flat in int TlvLightIndex;
#endif
layout(location = 0) out vec4 TlvAmbientOutput;
layout(location = 1) out vec4 TlvDirectionOutput;

void lfx_tlv_write_light(vec3 direction, vec3 light, float directionalContribution) {
    float luma = dot(light, LFX_TLV_LUMA);
    TlvAmbientOutput = vec4(0.282095 * light, directionalContribution);
    TlvDirectionOutput = vec4(0.488603 * vec3(-direction.y, direction.z, -direction.x) * luma, 0.0);
}

void main() {
    vec4 grid = TlvCascade[TlvAtlas.w];
    vec3 voxel = vec3(lfx_tlv_voxel(ivec2(gl_FragCoord.xy))) + 0.5;
    vec3 world = grid.xyz + voxel * grid.w;
#ifdef LFX_TLV_DIRECTIONAL_LIGHT
    vec3 direction = TlvDirectionalDirection.xyz;
    vec3 lightingPosition = (world - direction.xzy * (0.5 * grid.w)) * 0.01;
    float voxelDepth = dot(TlvDirectionalViewDepth, vec4(world * 0.01, 1.0));
    float lightingDepth = dot(TlvDirectionalViewDepth, vec4(lightingPosition, 1.0));
    vec2 visibility = lfx_tlv_directional_shadow(lightingPosition, voxelDepth, lightingDepth);
#ifdef LFX_TLV_MATERIAL_FUNCTION
    visibility.x *= texelFetch(TlvMaterialFunction, ivec2(gl_FragCoord.xy), 0).r;
#endif
    float shadow = TlvAtlas.w == 1 ? mix(visibility.y, visibility.x, lfx_tlv_border(voxel)) : visibility.x;
    lfx_tlv_write_light(direction, TlvDirectionalColor.rgb * (shadow / 3.14159265), shadow);
#else
#ifdef LFX_TLV_LOCAL_LIGHT
    vec4 centre = TlvLocalPositionRadius;
    vec4 color = TlvLocalColorExponent;
    float exposureBlend = TlvLocalParameters.x;
#else
    vec4 centre = texelFetch(ParticleLights, TlvLightIndex * 3);
    vec4 color = texelFetch(ParticleLights, TlvLightIndex * 3 + 1);
    float exposureBlend = texelFetch(ParticleLights, TlvLightIndex * 3 + 2).w;
#endif
    vec3 toLight = centre.xyz * 100.0 - world;
    float originalDistance = length(toLight);
    vec3 direction = originalDistance > 0.0 ? toLight / originalDistance : vec3(0.0);
    toLight += direction * (0.5 * grid.w);
    float inverseRadius = 1.0 / max(centre.w, 1e-10);
#ifdef LFX_TLV_LOCAL_LIGHT
    bool inverseSquared = TlvLocalParameters.z != 0.0;
#else
    bool inverseSquared = color.w == 0.0;
#endif
    float attenuation = lfx_point_radial_mask(toLight, inverseRadius, color.w, inverseSquared);
#ifdef LFX_TLV_LOCAL_LIGHT
    vec3 sourceToLight = toLight.xzy;
    if (TlvLocalRect.x > 0.0) {
        LfxRectLight rect = lfx_rect_light(sourceToLight, TlvLocalDirectionCone.xyz, TlvLocalTangent.xyz, TlvLocalRect);
        float integral, unusedNoL;
        lfx_rect_irradiance(rect, vec3(0.0), integral, unusedNoL);
        attenuation *= integral;
    } else {
        if (TlvLocalDirectionCone.w >= 0.0) attenuation *= lfx_spot_attenuation(direction.xzy,
                TlvLocalDirectionCone.xyz, vec2(TlvLocalDirectionCone.w, TlvLocalParameters.y));
        if (inverseSquared) attenuation /= dot(toLight, toLight) + 1.0;
    }
    if (attenuation > 0.0) attenuation *= lfx_volume_local_shadow(sourceToLight, inverseRadius);
    if (TlvAtlas.w == 1) {
        float border = lfx_tlv_border(voxel);
        attenuation *= border * border;
    }
#ifdef LFX_LOCAL_LIGHT_FUNCTION
    vec3 position = world.xzy;
    color.rgb *= lfx_local_light_function(position, length(position));
#elif defined(LFX_TLV_MATERIAL_FUNCTION)
    color.rgb *= texelFetch(TlvMaterialFunction, ivec2(gl_FragCoord.xy), 0).r;
#endif
#else
    if (inverseSquared) attenuation /= dot(toLight, toLight) + 1.0;
#endif
    vec3 light = color.rgb * (attenuation * exp(-exposureBlend * log(TlvExposure.x)) / 3.14159265);
    lfx_tlv_write_light(direction.xzy, light, 0.0);
#endif
}
