#version 330
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:decal_response.glsl>
#moj_import <lumenfx:scene_surface.glsl>
layout(std140) uniform ParticleLightView {
    mat4 LightProjection;
    vec4 LightViewportExposure;
    vec4 LightMaterialSettings;
};
uniform samplerBuffer ParticleLights;
uniform sampler2D SurfaceAlbedo;
uniform sampler2D SurfaceNormal;
uniform sampler2D SurfacePosition;
uniform sampler2D SceneDepth;
#ifdef LFX_POINT_COMPONENT
#moj_import <lumenfx:lighting/point_light_uniforms.glsl>
#moj_import <lumenfx:lighting/point_light_area.glsl>
#ifdef LFX_LOCAL_LIGHT_FUNCTION
#moj_import <lumenfx:lighting/light_function_atlas.glsl>
#endif
#ifdef LFX_RECT_LIGHT
#moj_import <lumenfx:lighting/rect_light_geometry.glsl>
#moj_import <lumenfx:lighting/rect_light_ltc.glsl>
#endif
#ifdef LFX_POINT_IES
#moj_import <lumenfx:lighting/point_light_profile.glsl>
uniform sampler2D PointIESProfile;
#endif
#ifdef LFX_POINT_ATTENUATION
uniform sampler2D PointAttenuation;
#endif
#endif
#ifdef LFX_POINT_CONTACT
#moj_import <lumenfx:lighting/contact_shadow.glsl>
#endif
#ifdef LFX_SCREEN_SPACE_AO
uniform sampler2D ScreenSpaceAo;
#endif
#if defined(LFX_DECAL_RECEIVER) || defined(LFX_NATIVE_PARTICLE_ANISOTROPY) || defined(LFX_CLEAR_COAT_BOTTOM_NORMAL) || defined(LFX_NATIVE_PARTICLE_SUBSTRATE_EYE) || LFX_SUBSTRATE_ADAPTIVE_LIGHT || (!defined(LFX_POINT_COMPONENT) && defined(LFX_NATIVE_LEGACY_SUBSURFACE_PROFILE))
#moj_import <lumenfx:decal_view.glsl>
#endif
#ifdef LFX_DECAL_RECEIVER
uniform sampler2D LfxDBufferColor;
uniform sampler2D LfxDBufferNormal;
uniform sampler2D NativeMaterialBase;
#endif
#ifdef LFX_HOST_MATERIAL
layout(std140) uniform HostMaterialView { mat4 HostInverseProjection; };
#ifdef LFX_HOST_SPECULAR
uniform sampler2D SurfaceLighting;
#endif
#endif
#ifdef LFX_NATIVE_PARTICLE_MATERIAL
#moj_import <lumenfx:lighting/default_lit_brdf.glsl>
#moj_import <lumenfx:lighting/reflection_energy.glsl>
#moj_import <lumenfx:lighting/clear_coat_normals.glsl>
#moj_import <lumenfx:lighting/clear_coat_transmittance.glsl>
#ifdef LFX_NATIVE_PARTICLE_TOON
#moj_import <lumenfx:substrate/toon_direct_light.glsl>
#endif
#ifdef LFX_NATIVE_PARTICLE_SUBSTRATE_EYE
#moj_import <lumenfx:substrate/eye_brdf.glsl>
#endif
#ifdef LFX_NATIVE_PARTICLE_SUBSTRATE
#moj_import <lumenfx:substrate/slab_encoding.glsl>
#moj_import <lumenfx:substrate/wrap_encoding.glsl>
#moj_import <lumenfx:substrate/slab_coat_normal.glsl>
    #if LFX_SUBSTRATE_VOLUME
uniform sampler2D LfxSlabPunctualVolume;
    #moj_import <lumenfx:substrate/simple_volume_lut.glsl>
    #moj_import <lumenfx:substrate/closure_tree.glsl>
    #endif
    #if LFX_SUBSTRATE_SPECULAR_PROFILE
    #moj_import <lumenfx:substrate/specular_profile.glsl>
    #endif
#moj_import <lumenfx:substrate/slab_brdf.glsl>
    #if LFX_SUBSTRATE_ADAPTIVE_LIGHT
    #moj_import <lumenfx:substrate/adaptive_closure_read.glsl>
    #endif
    #if LFX_SUBSTRATE_DIFFUSION
    #moj_import <lumenfx:substrate/diffusion_transmission.glsl>
    #endif
    #ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
    #moj_import <lumenfx:substrate/slab_basis.glsl>
    #endif
    #if LFX_SUBSTRATE_FUZZ
    #moj_import <lumenfx:substrate/sheen_ltc.glsl>
    #endif
#endif
#moj_import <lumenfx:lighting/unity_clear_coat.glsl>
uniform sampler2D SurfaceLighting;
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
uniform sampler2D SurfaceAnisotropy;
#endif
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
#moj_import <lumenfx:lighting/subsurface_brdf.glsl>
#moj_import <lumenfx:lighting/clear_coat_brdf.glsl>
#moj_import <lumenfx:lighting/cloth_brdf.glsl>
uniform sampler2D SurfaceCustomData;
uniform sampler2D SurfaceSourceMaterial;
#ifdef LFX_NATIVE_PREINTEGRATED_SKIN
uniform sampler2D LfxPreintegratedSkin;
#endif
#ifdef LFX_NATIVE_PARTICLE_HAIR
#moj_import <lumenfx:lighting/legacy_hair_brdf.glsl>
#endif
#ifdef LFX_NATIVE_LEGACY_SUBSURFACE_PROFILE
#moj_import <lumenfx:lighting/subsurface_profile_brdf.glsl>
#moj_import <lumenfx:lighting/legacy_eye_brdf.glsl>
#endif
#endif
#endif
flat in int lightIndex;
layout(location=0) out vec4 fragColor;
#ifdef LFX_SEPARATE_PROFILE_DIFFUSE
layout(location=1) out vec4 profileDiffuse;
#endif
#ifdef LFX_TOON_UNIFIED_DIFFUSE_TARGET
layout(location=LFX_TOON_UNIFIED_DIFFUSE_TARGET) out vec4 toonDiffuse;
layout(location=LFX_TOON_UNIFIED_WEIGHT_TARGET) out vec4 toonRampWeight;
#endif

vec3 lfx_light_surface_linear(vec3 value) {
    value = max(value, vec3(0.0));
#if LFX_WORLD_ENCODING == 2
    return pow(value, vec3(2.2));
#elif LFX_WORLD_ENCODING == 0
    return value;
#else
    return srgbToLinear(value);
#endif
}

#ifdef LFX_HOST_MATERIAL
vec3 lfx_light_octahedral(vec2 encoded) {
    vec3 normal = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (normal.z < 0.0) normal.xy = (1.0 - abs(normal.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), normal.xy));
    return normalize(normal);
}
#endif

void main() {
#ifdef LFX_SEPARATE_PROFILE_DIFFUSE
    profileDiffuse = vec4(0.0);
#endif
#ifdef LFX_TOON_UNIFIED_DIFFUSE_TARGET
    toonDiffuse = vec4(0.0);
    toonRampWeight = vec4(0.0);
#endif
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 surface = texelFetch(SurfaceAlbedo, pixel, 0);
#ifdef LFX_HOST_MATERIAL
    float depth = surface.a;
    if (depth < 0.0 || depth != texelFetch(SceneDepth, pixel, 0).r) discard;
    vec4 geometry = texelFetch(SurfaceNormal, pixel, 0);
    float logicalZ = 1.0 - 2.0 * depth;
    float viewZ = (HostInverseProjection[2].z * logicalZ + HostInverseProjection[3].z)
                / (HostInverseProjection[2].w * logicalZ + HostInverseProjection[3].w);
    vec3 position = vec3(geometry.xy, viewZ);
    vec3 surfaceNormal = lfx_light_octahedral(geometry.zw);
    float responseWeight = 1.0;
#else
    vec4 positionDepth = texelFetch(SurfacePosition, pixel, 0);
    if (surface.a == 0.0 || positionDepth.w != texelFetch(SceneDepth, pixel, 0).r) discard;
    vec3 position = lfx_surface_view_position(positionDepth, LightProjection, LFX_LIGHT_ZERO_TO_ONE != 0);
    vec3 surfaceNormal = texelFetch(SurfaceNormal, pixel, 0).xyz;
    float responseWeight = surface.a;
#endif
#ifdef LFX_POINT_COMPONENT
#ifdef LFX_HOST_MATERIAL
    if ((PointLightingChannels.x & 1) == 0) discard;
#else
    if ((PointLightingChannels.x & lfx_surface_light_channels(positionDepth)) == 0) discard;
#endif
#endif
    vec3 baseColor = lfx_light_surface_linear(surface.rgb);
#ifdef LFX_NATIVE_PARTICLE_MATERIAL
    bool defaultLitParticle = lfx_surface_has_default_lit_particle(positionDepth);
    vec4 particleLighting = defaultLitParticle ? texelFetch(SurfaceLighting, pixel, 0) : vec4(0.0);
#endif
#ifdef LFX_DECAL_RECEIVER
    if (lfx_surface_receives_decals(positionDepth)) {
        int responseMask = lfx_surface_decal_response(positionDepth);
        vec4 decalColor = texelFetch(LfxDBufferColor, pixel, 0);
        if ((responseMask & 1) != 0 && (decalColor.a != 1.0 || any(notEqual(decalColor.rgb, vec3(0.0))))) {
            vec4 material = texelFetch(NativeMaterialBase, pixel, 0);
            vec3 received = lfx_decal_base_color(srgbToLinear(material.rgb), decalColor);
            vec3 modified = surface.rgb + (linearToSrgb(received) - material.rgb) * (material.a - 1.0);
            baseColor = lfx_light_surface_linear(modified);
        }
        if ((responseMask & 2) != 0) surfaceNormal = lfx_decal_view_normal(surfaceNormal, texelFetch(LfxDBufferNormal, pixel, 0));
    }
#endif
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
    int shadingModel = lfx_surface_particle_shading_model(positionDepth);
    bool unityCoat = lfx_surface_uses_unity_coat(positionDepth);
    bool clearCoat = shadingModel == LFX_SHADING_MODEL_CLEAR_COAT && !unityCoat;
    vec4 custom = shadingModel > 1 ? texelFetch(SurfaceCustomData, pixel, 0) : vec4(0.0);
#if LFX_SUBSTRATE_WRAP
    bool substrateWrap = shadingModel == LFX_SHADING_MODEL_SUBSTRATE && lfx_slab_wrap_bits(custom.rgb) != 0u;
#endif
    vec3 topNormal = surfaceNormal;
#if defined(LFX_NATIVE_PARTICLE_SUBSTRATE) && defined(LFX_NATIVE_PARTICLE_ANISOTROPY)
    bool slabAnisotropy = shadingModel == LFX_SHADING_MODEL_SUBSTRATE && lfx_surface_has_anisotropic_particle(positionDepth);
    vec4 slabTangentAnisotropy = vec4(0.0);
    if (slabAnisotropy) {
        vec4 packedBasis = texelFetch(SurfaceAnisotropy, pixel, 0);
        vec3 worldNormal, worldTangent;
        lfx_substrate_unpack_normal_tangent(packedBasis, worldNormal, worldTangent);
        surfaceNormal = normalize(mat3(DecalWorldToViewNormal) * worldNormal.xzy);
        slabTangentAnisotropy = vec4(normalize(mat3(DecalWorldToViewNormal) * worldTangent.xzy), packedBasis.z * 2.0 - 1.0);
    }
#endif
#ifdef LFX_CLEAR_COAT_BOTTOM_NORMAL
    if (clearCoat) {
        vec3 worldNormal = (mat3(DecalViewToWorldNormal) * topNormal).xzy;
        vec2 octahedron = lfx_clear_coat_octahedron(worldNormal) + (custom.wz * 4.0 - 512.0 / 255.0);
        surfaceNormal = normalize(mat3(DecalWorldToViewNormal) * lfx_clear_coat_unit_vector(octahedron).xzy);
    }
#endif
#endif
    vec4 centreRadius = texelFetch(ParticleLights, lightIndex * 3);
    vec4 colorExponent = texelFetch(ParticleLights, lightIndex * 3 + 1);
    vec4 scales = texelFetch(ParticleLights, lightIndex * 3 + 2);
    vec3 toLight = (centreRadius.xyz - position) * 100.0;
#ifdef LFX_POINT_COMPONENT
    vec3 sourcePosition = (PointViewToSourceWorld * vec4(position, 1.0)).xyz;
#ifdef LFX_LOCAL_LIGHT_FUNCTION
    vec3 toCamera = PointViewToSourceWorld[3].xyz - sourcePosition;
    float functionDistance = length(toCamera);
    if (LightProjection[3][3] >= 1.0) {
        vec3 viewForward = normalize(mat3(PointViewToSourceWorld) * vec3(0.0, 0.0, -1.0));
        functionDistance *= functionDistance / dot(toCamera, viewForward);
    }
    colorExponent.rgb *= lfx_local_light_function(sourcePosition, functionDistance);
#endif
    vec3 sourceToLight = PointTranslatedPosition.xyz - sourcePosition;
    float distanceSquared = dot(sourceToLight, sourceToLight);
    float inverseRadius = 1.0 / max(0.00001, centreRadius.w);
#else
    float distanceSquared = dot(toLight, toLight);
    float inverseRadius = 1.0 / max(centreRadius.w, 0.0001);
#endif
    float normalizedSquared = distanceSquared * inverseRadius * inverseRadius;
    if (normalizedSquared >= 1.0) discard;
    float attenuation;
    if (colorExponent.w == 0.0) {
        float radiusMask = clamp(1.0 - normalizedSquared * normalizedSquared, 0.0, 1.0);
        attenuation = radiusMask * radiusMask / (distanceSquared + 1.0);
    } else attenuation = pow(1.0 - clamp(normalizedSquared, 0.0, 1.0), colorExponent.w);
    float noL = distanceSquared > 0.0 ? clamp(dot(surfaceNormal, toLight) * inversesqrt(distanceSquared), 0.0, 1.0) : 0.0;
    float exposureScale = LightViewportExposure.z <= 0.0 ? 1.0 : exp(-scales.w * log(LightViewportExposure.z));
    float surfaceShadow = 1.0;
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
    if (shadingModel == LFX_SHADING_MODEL_SUBSTRATE || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_HAIR
            || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_EYE || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_TOON)
        surfaceShadow = texelFetch(SurfaceNormal, pixel, 0).w;
#endif
#ifdef LFX_SCREEN_SPACE_AO
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
    else
#endif
        surfaceShadow = texelFetch(ScreenSpaceAo, pixel, 0).r;
#endif
    float transmissionShadow = 1.0;
    float subsurfaceTransmittance = 1.0;
    float contactVisibility = 1.0;
#if LFX_SUBSTRATE_ADAPTIVE_LIGHT
    vec2 adaptiveContact = vec2(0.0);
#endif
    vec3 response;
#ifdef LFX_RECT_LIGHT
    vec3 rectSpecular = vec3(0.0);
#endif
    vec3 sourceRadiance = vec3(1.0);
#ifdef LFX_POINT_COMPONENT
    vec3 sourceNormal = normalize(mat3(PointViewToSourceWorld) * surfaceNormal);
    vec3 sourceView = LightProjection[3][3] >= 1.0
            ? normalize(mat3(PointViewToSourceWorld) * vec3(0.0, 0.0, 1.0))
            : normalize(PointViewToSourceWorld[3].xyz - sourcePosition);
    bool inverseSquared = PointTranslatedPosition.w != 0.0;
    float lightMask = lfx_point_radial_mask(sourceToLight, inverseRadius, colorExponent.w, inverseSquared);
    if (PointCone.x >= 0.0) {
        float sourceDistanceSquared = dot(sourceToLight, sourceToLight);
        lightMask *= sourceDistanceSquared > 0.0
                ? lfx_spot_attenuation(sourceToLight * inversesqrt(sourceDistanceSquared), PointDirectionRadius.xyz, PointCone.xy)
                : 0.0;
    }
#ifdef LFX_POINT_IES
    lightMask *= lfx_light_profile_standalone(PointIESProfile, sourcePosition, PointTranslatedPosition.xyz,
            -PointDirectionRadius.xyz, PointTangentLength.xyz);
#endif
    LfxPointCapsule capsule = lfx_point_capsule(sourceToLight, PointTangentLength.xyz, PointTangentLength.w,
            PointDirectionRadius.w, PointAreaAndIndex.x);
    float pointRoughness = 0.5;
#ifdef LFX_HOST_SPECULAR
    pointRoughness = 1.0 - texelFetch(SurfaceLighting, pixel, 0).z;
#endif
#ifdef LFX_NATIVE_PARTICLE_MATERIAL
    if (defaultLitParticle) pointRoughness = particleLighting.w;
#endif
#ifdef LFX_RECT_LIGHT
    LfxRectLight rect = lfx_rect_light(sourceToLight, PointDirectionRadius.xyz, PointTangentLength.xyz, PointRectShape);
    if (any(lessThanEqual(rect.extent, vec2(0.0)))) discard;
    LfxPointAreaLight area = lfx_create_rect_area_light(rect, sourceNormal);
    sourceRadiance = lfx_rect_source_radiance(rect, area.diffuseDirection);
#else
    LfxPointAreaLight area = lfx_create_point_area_light(pointRoughness, sourceNormal, sourceView, capsule, inverseSquared);
#endif
    attenuation = lightMask * area.falloff;
    noL = area.normalDotLight;
    if (PointContactShadow.w != 0.0) surfaceShadow = 1.0;
#ifdef LFX_POINT_ATTENUATION
    vec4 encodedAttenuation = texelFetch(PointAttenuation, pixel, 0);
    surfaceShadow = encodedAttenuation.b * encodedAttenuation.b;
    transmissionShadow = encodedAttenuation.a * encodedAttenuation.a;
    subsurfaceTransmittance = transmissionShadow;
#endif
#endif
#ifdef LFX_POINT_CONTACT
#ifdef LFX_POINT_COMPONENT
    vec3 rayOrigin = sourcePosition, rayDirection = normalize(sourceToLight);
    float rayLength = PointContactShadow.w > 1.0
        ? abs(PointContactShadow.x) * (PointContactShadow.x < 0.0 ? 1.0
            : PointContactScreenScale.x * (-position.z * 100.0) + PointContactScreenScale.y) : 0.0;
    float shadowedBits = PointContactShadow.w;
    vec2 contactOcclusionFactors = PointContactShadow.yz;
#else
    vec3 rayOrigin = position * 100.0, rayDirection = normalize(toLight);
    float rayLength = 0.0, shadowedBits = 0.0;
    vec2 contactOcclusionFactors = vec2(1.0, 0.0);
#endif
    bool allowStartDepth = false;
#ifdef LFX_NATIVE_PARTICLE_HAIR
    if ((shadingModel == LFX_SHADING_MODEL_HAIR || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_HAIR) && shadowedBits < 2.0) {
        rayLength = 0.2 * (PointContactScreenScale.x * (-position.z * 100.0) + PointContactScreenScale.y);
        allowStartDepth = shadowedBits == 0.0;
    }
#endif
#ifdef LFX_NATIVE_LEGACY_SUBSURFACE_PROFILE
    if (shadingModel == LFX_SHADING_MODEL_EYE || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_EYE) rayLength = 0.5;
#endif
    if (rayLength > 0.0) {
        vec2 hitUV;
        float hit = lfx_cast_contact_ray(rayOrigin, rayDirection, rayLength,
                lfx_contact_dither(gl_FragCoord.xy), allowStartDepth, hitUV);
        if (hit > 0.0) {
            ivec2 hitPixel = ivec2(hitUV * LightViewportExposure.xy);
#ifdef LFX_HOST_MATERIAL
            float hitSurfaceDepth = texelFetch(SurfaceAlbedo, hitPixel, 0).a;
            bool castsContact = hitSurfaceDepth >= 0.0 && hitSurfaceDepth == textureLod(SceneDepth, hitUV, 0.0).r;
#else
            vec4 hitGeometry = texelFetch(SurfacePosition, hitPixel, 0);
            bool castsContact = hitGeometry.w == texelFetch(SceneDepth, hitPixel, 0).r && lfx_surface_casts_contact_shadow(hitGeometry);
#endif
            float contactOcclusion = castsContact ? contactOcclusionFactors.x : contactOcclusionFactors.y;
#if LFX_SUBSTRATE_ADAPTIVE_LIGHT
            adaptiveContact = vec2(contactOcclusion, hit);
#endif
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
            bool contactSubsurface = shadingModel == LFX_SHADING_MODEL_SUBSURFACE
                    || shadingModel == LFX_SHADING_MODEL_PREINTEGRATED_SKIN || shadingModel == LFX_SHADING_MODEL_TWOSIDED_FOLIAGE;
            float contactOpacity = custom.a;
#if LFX_SUBSTRATE_WRAP
            if (substrateWrap) {
                contactSubsurface = true;
                contactOpacity = lfx_slab_wrap_opacity(custom.rgb);
            }
#endif
            if (contactOcclusion > 0.0 && contactSubsurface) {
                float density = -0.05 * log(1.0 - min(contactOpacity, 0.99));
                contactOcclusion *= 1.0 - clamp(exp(-density * hit), 0.0, 1.0);
            }
#endif
            contactVisibility = 1.0 - contactOcclusion;
        }
    }
#endif
#ifdef LFX_NATIVE_PARTICLE_TOON
    float toonFalloff = attenuation;
#endif
    float transmissionAttenuation = attenuation * transmissionShadow * contactVisibility;
    attenuation *= surfaceShadow * contactVisibility;
    response = baseColor * (noL * attenuation * scales.x / 3.141592653589793);
#ifdef LFX_HOST_SPECULAR
    vec4 lighting = texelFetch(SurfaceLighting, pixel, 0);
    if (noL > 0.0 && lighting.w != 0.0 && scales.y != 0.0) {
        lfxHostGeometryNormal = lfx_light_octahedral(lighting.xy);
#ifdef LFX_RECT_LIGHT
        rectSpecular = lfx_rect_legacy_ggx(rect, sourceNormal, sourceView, 1.0 - lighting.z, vec3(lighting.w))
                * (scales.y * lightMask * surfaceShadow * contactVisibility);
#else
#ifdef LFX_POINT_COMPONENT
        vec3 specularDirection = normalize(transpose(mat3(PointViewToSourceWorld)) * area.specularDirection);
        float specular = GGX(surfaceNormal, normalize(position), specularDirection, noL, lighting.z);
#else
        float specular = GGX(surfaceNormal, normalize(position), toLight * inversesqrt(distanceSquared), noL, lighting.z);
#endif
        response += vec3(specular * lighting.w * scales.y * attenuation);
#endif
    }
#endif
#ifdef LFX_NATIVE_PARTICLE_MATERIAL
    if (defaultLitParticle && distanceSquared > 0.0) {
        vec3 view = LightProjection[3][3] >= 1.0 ? vec3(0.0, 0.0, 1.0) : normalize(-position);
#ifdef LFX_NATIVE_PARTICLE_TOON
        if (shadingModel == LFX_SHADING_MODEL_SUBSTRATE_TOON) {
            int profile = lfx_toon_profile_layer(custom);
            vec2 patternUv = lfx_toon_pattern_uv(custom);
            vec4 toonSettings = lfx_toon_settings(profile, 0);
            bool includeShadow = lfx_toon_settings(profile, 1).x > 0.0;
            if (!includeShadow && surfaceShadow + transmissionShadow <= 0.0) discard;
#ifdef LFX_POINT_COMPONENT
            vec3 toonL = normalize(transpose(mat3(PointViewToSourceWorld)) * sourceToLight);
#else
            vec3 toonL = toLight * inversesqrt(distanceSquared);
#endif
            float transmittance = lfx_toon_shadow_hatching(profile, patternUv, subsurfaceTransmittance);
            float diffuseNoL = dot(surfaceNormal, toonL) * 0.5 + 0.5;
            if (includeShadow) diffuseNoL *= transmittance;
            vec3 diffuse = baseColor * (scales.x / LFX_BRDF_PI);
#ifndef LFX_TOON_UNIFIED_DIFFUSE_TARGET
            diffuse *= lfx_toon_ramp(profile, 0,
                    clamp(diffuseNoL + lfx_toon_diffuse_offset(profile, patternUv, toonSettings), 0.0, 1.0));
#endif
            if (!includeShadow) diffuse *= transmittance;
            float roughness = max(particleLighting.w, 0.02);
            float rawSpecular;
#ifdef LFX_RECT_LIGHT
            vec3 rectTint = lfx_rect_ggx(rect, sourceNormal, sourceView, roughness, particleLighting.rgb, vec3(1.0));
            rawSpecular = dot(rectTint, vec3(1.0 / 3.0));
#else
            vec4 toonTangent = vec4(0.0);
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
            if (lfx_surface_has_anisotropic_particle(positionDepth)) {
                toonTangent = texelFetch(SurfaceAnisotropy, pixel, 0) * 2.0 - 1.0;
                toonTangent.xyz = normalize(mat3(DecalWorldToViewNormal) * toonTangent.xzy);
            }
#endif
            rawSpecular = lfx_toon_ggx(roughness, surfaceNormal, view, toonL, toonTangent);
#endif
            vec3 specularRamp = lfx_toon_ramp(profile, 1,
                    clamp(rawSpecular + lfx_toon_specular_offset(profile, patternUv, toonSettings), 0.0, 1.0));
            vec3 specular = particleLighting.rgb * specularRamp * (noL * surfaceShadow * contactVisibility * scales.y);
            vec3 lightColor = colorExponent.rgb * (toonFalloff * custom.z * exposureScale * LightViewportExposure.w * responseWeight);
            vec3 radiance = sourceRadiance * lightColor;
#ifdef LFX_TOON_UNIFIED_DIFFUSE_TARGET
            float weight = dot(lightColor, vec3(0.2126390059, 0.7151686788, 0.0721923154)) * transmittance;
            toonDiffuse = vec4(diffuse * radiance, 0.0);
            toonRampWeight = vec4(weight, weight * diffuseNoL, 0.0, 0.0);
            diffuse = vec3(0.0);
#endif
            fragColor = vec4((diffuse + specular) * radiance, 0.0);
            return;
        }
#endif
#ifdef LFX_POINT_COMPONENT
        vec3 direction = normalize(transpose(mat3(PointViewToSourceWorld)) * area.specularDirection);
        vec3 diffuseDirection = normalize(transpose(mat3(PointViewToSourceWorld)) * area.diffuseDirection);
        vec3 areaParameters = vec3(area.sphereSinAlpha, area.sphereSinAlphaSoft, area.lineCosSubtended);
        float retroreflection = area.diffuseMicroReflectionWeight;
#else
        vec3 direction = toLight * inversesqrt(distanceSquared);
        vec3 diffuseDirection = direction;
        vec3 areaParameters = vec3(0.0, 0.0, 1.0);
        float retroreflection = 1.0;
#endif
#if defined(LFX_NATIVE_PARTICLE_SUBSTRATE_EYE) || defined(LFX_NATIVE_PARTICLE_HAIR) || LFX_SUBSTRATE_WRAP || LFX_SUBSTRATE_ADAPTIVE_LIGHT
#ifdef LFX_POINT_COMPONENT
        vec3 centreDirection = normalize(transpose(mat3(PointViewToSourceWorld)) * sourceToLight);
#else
        vec3 centreDirection = direction;
#endif
#endif
#ifdef LFX_NATIVE_LEGACY_SUBSURFACE_PROFILE
        if (lfx_surface_uses_subsurface_profile(shadingModel)) {
            vec3 diffuse = vec3(0.0), specular = vec3(0.0);
            vec3 transmission = vec3(0.0);
            uint stored = uint(texelFetch(SurfaceSourceMaterial, pixel, 0).a);
            vec2 storedMetalSpecular = vec2(float(stored & 255u), float((stored >> 8) & 255u)) / 255.0;
            bool eye = shadingModel == LFX_SHADING_MODEL_EYE || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_EYE;
            vec3 diffuseColor = baseColor;
#ifdef LFX_SEPARATE_PROFILE_DIFFUSE
            diffuseColor = vec3(eye ? 1.0 : 1.0 - storedMetalSpecular.x);
#endif
#ifdef LFX_NATIVE_PARTICLE_SUBSTRATE_EYE
            if (shadingModel == LFX_SHADING_MODEL_SUBSTRATE_EYE) {
                mat3 toView = mat3(DecalWorldToViewNormal);
                vec3 iris = normalize(toView * lfx_substrate_eye_normal(custom.g).xzy);
                vec3 plane = normalize(toView * lfx_substrate_eye_normal(custom.b).xzy);
                lfx_substrate_eye_light(particleLighting, storedMetalSpecular, diffuseColor, surfaceNormal, iris,
                        plane, view, centreDirection, direction, noL, areaParameters, transmission, specular);
                transmission *= transmissionAttenuation;
            } else
#endif
            if (eye) {
#ifdef LFX_POINT_COMPONENT
                vec3 eyeNormal = sourceNormal, eyeView = sourceView, eyeDirection = area.specularDirection;
#else
                mat3 toSourceWorld = mat3(DecalViewToWorldNormal);
                vec3 eyeNormal = (toSourceWorld * surfaceNormal).xzy;
                vec3 eyeView = (toSourceWorld * view).xzy;
                vec3 eyeDirection = (toSourceWorld * direction).xzy;
#endif
                lfx_legacy_eye_light(particleLighting, custom, storedMetalSpecular, diffuseColor, eyeNormal, eyeView,
                        eyeDirection, noL, areaParameters, transmission, specular);
                transmission *= transmissionAttenuation;
            } else {
#ifndef LFX_RECT_LIGHT
                if (noL > 0.0)
#endif
                    lfx_subsurface_profile_light(particleLighting, custom, diffuseColor, surfaceNormal, view, direction,
                            noL, areaParameters, retroreflection,
#ifdef LFX_RECT_LIGHT
                            rect, sourceNormal, sourceView,
#endif
                            diffuse, specular);
#ifdef LFX_POINT_COMPONENT
                if (PointLightingChannels.y != 0)
                    transmission = lfx_subsurface_profile_transmission(custom, subsurfaceTransmittance, surfaceNormal, view,
                            diffuseDirection) * transmissionAttenuation;
#endif
            }
            vec3 lightColor = colorExponent.rgb * (exposureScale * LightViewportExposure.w * responseWeight);
            diffuse = (diffuse * (noL * attenuation) + transmission) * (lightColor * sourceRadiance * scales.x);
#ifdef LFX_RECT_LIGHT
            if (eye) {
                vec3 eyeF0 = vec3(particleLighting.r);
                float eyeNoV = clamp(abs(dot(sourceNormal, sourceView)) + 1e-5, 0.0, 1.0);
#ifdef LFX_NATIVE_PARTICLE_SUBSTRATE_EYE
                if (shadingModel == LFX_SHADING_MODEL_SUBSTRATE_EYE) {
                    vec3 eyeF90 = vec3(1.0);
                    LfxReflectionEnergy eyeEnergy = lfx_ggx_reflection_energy(particleLighting.w, eyeNoV, eyeF0, eyeF90,
                            LFX_SUBSTRATE_CONSERVATION != 0, LFX_SUBSTRATE_PRESERVATION != 0);
                    specular = lfx_rect_ggx(rect, sourceNormal, sourceView, max(particleLighting.w, 0.02), eyeF0, eyeF90) * eyeEnergy.multiScatter;
                } else
#endif
                {
                    LfxReflectionEnergy eyeEnergy = lfx_legacy_reflection_energy(particleLighting.w, eyeNoV, eyeF0);
                    specular = lfx_rect_legacy_ggx(rect, sourceNormal, sourceView, particleLighting.w, eyeF0) * eyeEnergy.multiScatter;
                }
            }
            specular *= lightColor * (lightMask * surfaceShadow * contactVisibility * scales.y);
#else
            specular *= lightColor * (noL * attenuation * scales.y);
#endif
#ifdef LFX_SEPARATE_PROFILE_DIFFUSE
            profileDiffuse = vec4(diffuse, 0.0);
            fragColor = vec4(specular, 0.0);
#else
            fragColor = vec4(diffuse + specular, 0.0);
#endif
            return;
        }
#endif
#ifdef LFX_NATIVE_PARTICLE_HAIR
        if (shadingModel == LFX_SHADING_MODEL_HAIR || shadingModel == LFX_SHADING_MODEL_SUBSTRATE_HAIR) {
            vec4 source = texelFetch(SurfaceSourceMaterial, pixel, 0);
            uint packedValue = uint(source.a);
            bool substrateHair = shadingModel == LFX_SHADING_MODEL_SUBSTRATE_HAIR;
            vec3 hairDirection = substrateHair ? centreDirection : diffuseDirection;
            float hairVisibility = substrateHair ? 1.0 : surfaceShadow * contactVisibility;
            float hairScale = substrateHair ? scales.y : scales.x;
            response = lfx_hair_legacy_light(source.rgb, float(packedValue & 255u) / 255.0, float((packedValue >> 8) & 255u) / 255.0,
                    particleLighting.w, surfaceNormal, view, hairDirection, hairVisibility,
                    transmissionShadow * contactVisibility) * (transmissionAttenuation * hairScale);
            fragColor = vec4(response * sourceRadiance * colorExponent.rgb * (exposureScale * LightViewportExposure.w * responseWeight), 0.0);
            return;
        }
#endif
#ifdef LFX_NATIVE_PARTICLE_SUBSTRATE
        if (shadingModel == LFX_SHADING_MODEL_SUBSTRATE) {
#if LFX_SUBSTRATE_ADAPTIVE_LIGHT
            uvec4 header = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
            int closureCount = int(header.x);
            if (closureCount > 0) {
                vec3 total = vec3(0.0);
                vec3 scattered = vec3(0.0);
                int offset = lfx_adaptive_first_closure(header);
                for (int index = 0; index < closureCount; index++) {
                    LfxAdaptiveSlab slab = lfx_read_adaptive_slab(pixel, offset, mat3(DecalWorldToViewNormal));
                    if (!any(greaterThan(slab.luminance, vec3(0.0)))) continue;
                    vec3 slabLight = direction;
                    vec3 slabDiffuseLight = diffuseDirection;
                    vec3 slabArea = areaParameters;
                    float falloff = attenuation;
                    float transmittedFalloff = transmissionAttenuation;
                    float slabRetroreflection = retroreflection;
                    float slabNoL = clamp(dot(slab.normal, slabLight), 0.0, 1.0);
#ifdef LFX_POINT_COMPONENT
                    vec3 slabSourceNormal = normalize(mat3(PointViewToSourceWorld) * slab.normal);
#ifdef LFX_RECT_LIGHT
                    LfxPointAreaLight slabPoint = lfx_create_rect_area_light(rect, slabSourceNormal);
                    vec3 leafRadiance = lfx_rect_source_radiance(rect, slabPoint.diffuseDirection);
#else
                    LfxPointAreaLight slabPoint = lfx_create_point_area_light(slab.specular.w, slabSourceNormal, sourceView, capsule, inverseSquared);
                    vec3 leafRadiance = sourceRadiance;
#endif
                    mat3 pointToView = transpose(mat3(PointViewToSourceWorld));
                    slabLight = normalize(pointToView * slabPoint.specularDirection);
                    slabDiffuseLight = normalize(pointToView * slabPoint.diffuseDirection);
                    slabArea = vec3(slabPoint.sphereSinAlpha, slabPoint.sphereSinAlphaSoft, slabPoint.lineCosSubtended);
                    slabNoL = slabPoint.normalDotLight;
                    slabRetroreflection = slabPoint.diffuseMicroReflectionWeight;
                    float leafOcclusion = adaptiveContact.x;
                    if (slab.wrap != 0 && leafOcclusion > 0.0) {
                        float opacity = min(1.0 - abs(slab.phase), 0.99);
                        float density = -0.05 * log(1.0 - opacity);
                        leafOcclusion *= 1.0 - clamp(exp(-density * adaptiveContact.y), 0.0, 1.0);
                    }
                    float leafMask = lightMask * slabPoint.falloff * (1.0 - leafOcclusion);
                    falloff = leafMask * surfaceShadow;
                    transmittedFalloff = leafMask * transmissionShadow;
#else
                    vec3 leafRadiance = sourceRadiance;
#endif
                    float slabDiffuseNoL = slabNoL;
                    if (slab.simpleCoat) {
#ifdef LFX_RECT_LIGHT
                        float baseFalloff;
                        vec3 baseLight = lfx_rect_irradiance(rect, normalize(mat3(PointViewToSourceWorld) * slab.baseNormal),
                                baseFalloff, slabDiffuseNoL);
                        slabDiffuseLight = normalize(pointToView * baseLight);
                        leafRadiance = lfx_rect_source_radiance(rect, baseLight);
#else
                        slabDiffuseNoL = clamp(dot(slab.baseNormal, slabDiffuseLight), 0.0, 1.0);
#ifdef LFX_POINT_COMPONENT
                        if (slabArea.x > 0.0) slabDiffuseNoL = lfx_point_sphere_horizon(slabDiffuseNoL, slabArea.x * slabArea.x);
#endif
#endif
                    }
                    vec3 lightResponse = vec3(0.0);
                    vec3 specularResponse = vec3(0.0);
                    vec3 diffuse = slab.diffuse;
                    bool scatter = false;
#ifdef LFX_SEPARATE_PROFILE_DIFFUSE
                    scatter = slab.diffusion && (header.z & LFX_ADAPTIVE_DIFFUSION_PRESENT) != 0u;
                    if (scatter) diffuse = vec3(1.0);
#endif
#ifndef LFX_RECT_LIGHT
                    if (slabNoL > 0.0 || slabDiffuseNoL > 0.0) {
#endif
                        lightResponse = lfx_substrate_slab_light(diffuse, slab.specular, slab.edge, slab.haze, slab.simpleCoat,
                                slab.baseNormal, slab.normal, view, slabLight, slabNoL, slabDiffuseNoL, slabArea, slabRetroreflection,
                                slab.tangentAnisotropy, slab.anisotropic, scales.xy, slab.specularProfile, slab.volumeMfp, slab.phase,
#ifdef LFX_RECT_LIGHT
                                rect, mat3(PointViewToSourceWorld),
#endif
                                specularResponse) * falloff;
#ifdef LFX_RECT_LIGHT
                        float rectVisibility = lightMask * (1.0 - leafOcclusion) * surfaceShadow;
                        specularResponse *= rectVisibility;
#else
                        specularResponse *= falloff;
                    }
#endif
#if LFX_SUBSTRATE_DIFFUSION && defined(LFX_POINT_COMPONENT)
                    if (slab.diffusion && PointLightingChannels.y != 0) {
                        lightResponse += lfx_slab_diffusion_transmission(slab, diffuse, subsurfaceTransmittance,
                                view, centreDirection) * (transmittedFalloff * scales.x);
                    }
#endif
#if LFX_SUBSTRATE_WRAP
                    if (slab.wrap != 0) {
                        vec3 transmittance = exp(-max(vec3(1e-6), 1.0 / max(slab.wrapMfp, vec3(1e-12))));
                        lightResponse += lfx_subsurface_wrap_light(transmittance, slab.wrap == 2, 1.0 - abs(slab.phase),
                                1.0, slab.normal, view, slab.wrap == 2 ? centreDirection : slabDiffuseLight,
                                subsurfaceTransmittance, LightMaterialSettings.x) * (transmittedFalloff * scales.x);
                    }
#endif
#if LFX_SUBSTRATE_FUZZ
                    if (slab.fuzz.x > 0.0) {
#ifdef LFX_RECT_LIGHT
                        vec3 sheen = lfx_substrate_sheen_ltc_coefficients(dot(slab.normal, view), slab.fuzz.y);
                        float fuzzTransmission = 1.0 - sheen.z * slab.fuzz.x;
                        vec3 sheenSpecular = lfx_rect_sheen(rect, slabSourceNormal, sourceView, sheen);
                        lightResponse *= fuzzTransmission;
                        specularResponse = specularResponse * fuzzTransmission
                                + slab.fuzzColor * sheenSpecular * (slab.fuzz.x * scales.y * rectVisibility);
#else
                        float albedo;
                        float lobe = lfx_substrate_sheen_ltc(slab.normal, view, slabDiffuseLight, slab.fuzz.y, albedo);
                        float fuzzTransmission = 1.0 - albedo * slab.fuzz.x;
                        lightResponse *= fuzzTransmission;
                        specularResponse = specularResponse * fuzzTransmission
                                + slab.fuzzColor * (lobe * albedo * slab.fuzz.x * scales.y * falloff);
#endif
                    }
#endif
                    vec3 weight = lfx_adaptive_luminance(slab, centreDirection);
                    lightResponse *= leafRadiance;
#ifndef LFX_RECT_LIGHT
                    specularResponse *= leafRadiance;
#endif
                    total += (specularResponse + (scatter ? vec3(0.0) : lightResponse)) * weight;
                    if (scatter) scattered += lightResponse * weight;
                }
                vec3 lightColor = colorExponent.rgb * (exposureScale * LightViewportExposure.w * responseWeight);
                fragColor = vec4(total * lightColor, 0.0);
#ifdef LFX_SEPARATE_PROFILE_DIFFUSE
                profileDiffuse = vec4(scattered * lightColor, 0.0);
#endif
                return;
            }
#endif
            response = vec3(0.0);
            uint packedValue = uint(custom.a);
            float coverage = float(packedValue >> 16) / 255.0;
            vec2 haze = vec2(float(packedValue & 255u) / 255.0, float((packedValue >> 8) & 127u) / 127.0);
            bool simpleCoat = (packedValue & 32768u) != 0u;
            vec3 baseNormal = surfaceNormal;
            float diffuseNoL = noL;
#ifdef LFX_CLEAR_COAT_BOTTOM_NORMAL
            uint coatNormal = lfx_wrap_magnitude(custom.g) & 65535u;
            if (simpleCoat && coatNormal != 0u) {
                vec3 topWorld = normalize(mat3(DecalViewToWorldNormal) * surfaceNormal).xzy;
                vec3 baseWorld = lfx_decode_slab_coat_normal(coatNormal, topWorld);
                baseNormal = normalize(mat3(DecalWorldToViewNormal) * baseWorld.xzy);
#ifdef LFX_RECT_LIGHT
                float baseFalloff;
                vec3 baseLight = lfx_rect_irradiance(rect, normalize(mat3(PointViewToSourceWorld) * baseNormal), baseFalloff, diffuseNoL);
                diffuseDirection = normalize(transpose(mat3(PointViewToSourceWorld)) * baseLight);
                sourceRadiance = lfx_rect_source_radiance(rect, baseLight);
#else
                diffuseNoL = clamp(dot(baseNormal, diffuseDirection), 0.0, 1.0);
#ifdef LFX_POINT_COMPONENT
                if (areaParameters.x > 0.0)
                    diffuseNoL = lfx_point_sphere_horizon(diffuseNoL, areaParameters.x * areaParameters.x);
#endif
#endif
            }
#endif
#if LFX_SUBSTRATE_SPECULAR_PROFILE || LFX_SUBSTRATE_FUZZ
            vec4 slabSource = texelFetch(SurfaceSourceMaterial, pixel, 0);
#endif
#ifdef LFX_RECT_LIGHT
            float rectVisibility = lightMask * surfaceShadow * contactVisibility;
            if (coverage > 0.0) {
#else
            if ((noL > 0.0 || diffuseNoL > 0.0) && coverage > 0.0) {
#endif
                bool anisotropic = false;
                vec4 tangentAnisotropy = vec4(0.0);
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
                anisotropic = slabAnisotropy;
                tangentAnisotropy = slabTangentAnisotropy;
#endif
                vec3 edgeColor = lfx_substrate_unpack_edge_color(lfx_wrap_magnitude(custom.r) & 131071u);
                float specularProfile = 0.0;
#if LFX_SUBSTRATE_SPECULAR_PROFILE
                specularProfile = lfx_slab_profile_id(slabSource);
#endif
                vec3 specularResponse;
                response = lfx_substrate_slab_light(srgbToLinear(surface.rgb), particleLighting, edgeColor, haze, simpleCoat,
                        baseNormal, surfaceNormal, view, direction, noL, diffuseNoL, areaParameters, retroreflection,
                        tangentAnisotropy, anisotropic, scales.xy, specularProfile, vec3(0.0), 0.0,
#ifdef LFX_RECT_LIGHT
                        rect, mat3(PointViewToSourceWorld),
#endif
                        specularResponse);
#ifdef LFX_RECT_LIGHT
                response *= attenuation;
                rectSpecular = specularResponse * rectVisibility;
#else
                response = (response + specularResponse) * attenuation;
#endif
            }
#if LFX_SUBSTRATE_WRAP
            if (substrateWrap && coverage > 0.0) {
                bool twoSided = custom.g < 0.0;
                response += lfx_subsurface_wrap_light(lfx_slab_wrap_transmittance(custom.rgb), twoSided,
                        lfx_slab_wrap_opacity(custom.rgb), 1.0, surfaceNormal, view,
                        twoSided ? centreDirection : diffuseDirection, subsurfaceTransmittance, LightMaterialSettings.x)
                        * (transmissionAttenuation * scales.x);
            }
#endif
#if LFX_SUBSTRATE_FUZZ
            if (coverage > 0.0) {
                uint fuzz = uint(slabSource.a);
                float fuzzAmount = float(fuzz & 63u) / 63.0;
                if (fuzzAmount > 0.0) {
                    float fuzzRoughness = float((fuzz >> 6) & 63u) / 63.0;
#ifdef LFX_RECT_LIGHT
                    vec3 sheen = lfx_substrate_sheen_ltc_coefficients(dot(surfaceNormal, view), fuzzRoughness);
                    float fuzzTransmission = 1.0 - sheen.z * fuzzAmount;
                    response *= fuzzTransmission;
                    rectSpecular = rectSpecular * fuzzTransmission + lfx_slab_fuzz_color(slabSource)
                            * lfx_rect_sheen(rect, sourceNormal, sourceView, sheen) * (fuzzAmount * scales.y * rectVisibility);
#else
                    float albedo;
                    float fuzzLobe = lfx_substrate_sheen_ltc(surfaceNormal, view, diffuseDirection, fuzzRoughness, albedo);
                    response = response * (1.0 - albedo * fuzzAmount)
                            + lfx_slab_fuzz_color(slabSource) * (fuzzLobe * albedo * fuzzAmount * scales.y * attenuation);
#endif
                }
            }
#endif
            response *= sourceRadiance;
#ifdef LFX_RECT_LIGHT
            response += rectSpecular;
#endif
            fragColor = vec4(response * colorExponent.rgb * (coverage * exposureScale * LightViewportExposure.w * responseWeight), 0.0);
            return;
        }
#endif
        if (lfx_surface_uses_urp_lit(positionDepth)) {
            vec3 lobes = lfx_unity_coat_direct(baseColor, particleLighting, vec2(0.0), surfaceNormal, view, direction, scales.xy);
            fragColor = vec4(lobes * noL * attenuation * sourceRadiance * colorExponent.rgb
                    * (exposureScale * LightViewportExposure.w * responseWeight), 0.0);
            return;
        }
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
        if (unityCoat) {
            vec3 coated = lfx_unity_coat_direct(baseColor, particleLighting, custom.xy, surfaceNormal, view, direction, scales.xy);
            fragColor = vec4(coated * noL * attenuation * sourceRadiance * colorExponent.rgb
                    * (exposureScale * LightViewportExposure.w * responseWeight), 0.0);
            return;
        }
        bool standardLit = !clearCoat && shadingModel != LFX_SHADING_MODEL_CLOTH;
#else
        bool standardLit = true;
#endif
        float rawNoV = dot(surfaceNormal, view);
        LfxReflectionEnergy standardEnergy = lfx_legacy_reflection_energy(particleLighting.w,
                clamp(abs(rawNoV) + 1e-5, 0.0, 1.0), particleLighting.rgb);
        if (noL > 0.0 && standardLit) {
#if LFX_LEGACY_ROUGH_DIFFUSE
            float voL = dot(view, direction);
            response = lfx_diffuse_eon(baseColor, retroreflection * particleLighting.w * 0.4,
                    clamp(rawNoV, 0.0, 1.0), noL, voL) * (noL * attenuation * scales.x);
#endif
            response *= standardEnergy.diffuseTransmission;
        }
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
#ifdef LFX_NATIVE_PREINTEGRATED_SKIN
        if (shadingModel == LFX_SHADING_MODEL_PREINTEGRATED_SKIN) {
            float lightingAngle = clamp(dot(surfaceNormal, diffuseDirection) * 0.5 + 0.5, 0.0, 1.0);
            vec3 transmittance = textureLod(LfxPreintegratedSkin, vec2(lightingAngle, 1.0 - custom.a), 0.0).rgb;
            response += transmittance * custom.rgb * custom.rgb * (transmissionAttenuation * scales.x);
        }
#endif
        if (shadingModel == LFX_SHADING_MODEL_SUBSURFACE || shadingModel == LFX_SHADING_MODEL_TWOSIDED_FOLIAGE) {
            float materialAo = LFX_LEGACY_STATIC_LIGHTING != 0 ? 1.0 : texelFetch(SurfaceNormal, pixel, 0).w;
            response += lfx_subsurface_transmission(shadingModel, custom, materialAo, surfaceNormal, view, diffuseDirection,
                    subsurfaceTransmittance, LightMaterialSettings.x) * (transmissionAttenuation * scales.x);
        }
        bool specularRequired = scales.y != 0.0 || !standardLit;
#else
        bool specularRequired = scales.y != 0.0;
#endif
#ifdef LFX_RECT_LIGHT
        if (specularRequired) {
#else
        if (noL > 0.0 && specularRequired) {
#endif
            float sphere = areaParameters.x, softSphere = areaParameters.y, lineCos = areaParameters.z;
            bool anisotropic = false;
            vec4 tangentAnisotropy = vec4(0.0);
#if defined(LFX_NATIVE_PARTICLE_ANISOTROPY) && defined(LFX_POINT_COMPONENT)
            anisotropic = lfx_surface_has_anisotropic_particle(positionDepth);
            if (anisotropic) {
                tangentAnisotropy = texelFetch(SurfaceAnisotropy, pixel, 0) * 2.0 - 1.0;
                tangentAnisotropy.xyz = normalize(mat3(DecalWorldToViewNormal) * tangentAnisotropy.xzy);
            }
#endif
            vec3 specular;
#ifdef LFX_NATIVE_PARTICLE_CUSTOM_DATA
            if (clearCoat) {
                vec4 source = texelFetch(SurfaceSourceMaterial, pixel, 0);
                float metallic = float(int(source.a) & 255) / 255.0;
                LfxClearCoatLighting coating = lfx_clear_coat_lighting(custom, source.rgb, metallic, particleLighting,
                        surfaceNormal, topNormal, view, direction, noL, sphere, softSphere, lineCos, anisotropic,
#ifdef LFX_RECT_LIGHT
                        rect, mat3(PointViewToSourceWorld), area.falloff, sourceRadiance,
#endif
                        tangentAnisotropy);
                response *= coating.diffuseWeight;
                specular = coating.specular;
            } else if (shadingModel == LFX_SHADING_MODEL_CLOTH) {
                float diffuseWeight;
                specular = lfx_cloth_lighting(custom, particleLighting, surfaceNormal, view, direction,
                        noL, sphere, softSphere, lineCos,
#ifdef LFX_RECT_LIGHT
                        rect, mat3(PointViewToSourceWorld), area.falloff, sourceRadiance,
#endif
                        diffuseWeight);
                response *= diffuseWeight;
            } else
#endif
#ifdef LFX_NATIVE_PARTICLE_ANISOTROPY
            if (anisotropic) {
                specular = lfx_default_lit_anisotropic_specular(particleLighting.rgb, particleLighting.w, tangentAnisotropy.w,
                        surfaceNormal, tangentAnisotropy.xyz, view, direction, noL);
            } else
#endif
            {
                specular = lfx_default_lit_specular(particleLighting.rgb, particleLighting.w, surfaceNormal, view, direction,
                        noL, sphere, softSphere, lineCos);
            }
#ifdef LFX_RECT_LIGHT
            if (!standardLit) {
                rectSpecular = specular * (scales.y * lightMask * surfaceShadow * contactVisibility);
            } else if (!anisotropic) {
                rectSpecular = lfx_rect_legacy_ggx(rect, sourceNormal, sourceView, particleLighting.w, particleLighting.rgb)
                        * standardEnergy.multiScatter * (scales.y * lightMask * surfaceShadow * contactVisibility);
            } else
#endif
            {
                if (standardLit) specular *= standardEnergy.multiScatter;
                response += specular * (noL * scales.y * attenuation);
            }
        }
    }
#endif
    response *= sourceRadiance;
#ifdef LFX_RECT_LIGHT
    response += rectSpecular;
#endif
    fragColor = vec4(response * colorExponent.rgb * (exposureScale * LightViewportExposure.w * responseWeight), 0.0);
}
