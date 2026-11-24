#ifndef LFX_LEGACY_ENERGY
#define LFX_LEGACY_ENERGY 0
#endif
#ifndef LFX_LEGACY_ENERGY_CONSERVE
#define LFX_LEGACY_ENERGY_CONSERVE 0
#endif
#ifndef LFX_LEGACY_ENERGY_PRESERVE
#define LFX_LEGACY_ENERGY_PRESERVE 0
#endif
#moj_import <lumenfx:lighting/default_lit_brdf.glsl>
#moj_import <lumenfx:lighting/point_light_area.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:lighting/subsurface_brdf.glsl>
#moj_import <lumenfx:lighting/light_function_sampling.glsl>
#moj_import <lumenfx:lighting/point_light_profile.glsl>
#moj_import <lumenfx:lighting/spot_light_shadow.glsl>
#moj_import <lumenfx:lighting/forward_point_shadow.glsl>
#moj_import <lumenfx:lighting/forward_directional_shadow.glsl>
#moj_import <lumenfx:lighting/forward_atmosphere_transmittance.glsl>
#moj_import <lumenfx:lighting/translucent_surface_environment.glsl>
#moj_import <lumenfx:lighting/reflection_energy.glsl>
#if LFX_FORWARD_RECT_LIGHTS
#define LFX_RECT_LIGHT_ATLAS
#define LFX_RECT_LIGHT
#moj_import <lumenfx:lighting/rect_light_geometry.glsl>
#moj_import <lumenfx:lighting/rect_light_ltc.glsl>
#endif
#moj_import <lumenfx:lighting/clear_coat_brdf.glsl>

layout(std140) uniform LfxForwardLightGrid {
    ivec4 ForwardGridShape;
    vec4 ForwardGridZ;
    mat4 ForwardWorldToView;
    vec4 ForwardMaterialSettings;
    vec4 ForwardDirectionalDirection;
    vec4 ForwardDirectionalColor;
    vec2 ForwardDirectionalScales;
    ivec2 ForwardDirectionalFlags;
};
uniform usamplerBuffer LfxForwardLightHeaders;
uniform usamplerBuffer LfxForwardLightIndices;
uniform samplerBuffer LfxForwardLights;
uniform samplerBuffer LfxForwardLightFunctions;
uniform sampler2D LfxForwardLightFunctionAtlas;
uniform sampler2DArray LfxForwardLightProfiles;
uniform sampler2D LfxForwardSpotShadowAtlas;
layout(std140) uniform LfxForwardPrimitive {
    ivec4 ForwardPrimitiveChannels;
};

vec3 lfx_forward_light_function(int slot, vec3 sourcePosition) {
    int base = slot * 6;
    mat4 worldToLight = mat4(texelFetch(LfxForwardLightFunctions, base), texelFetch(LfxForwardLightFunctions, base + 1),
            texelFetch(LfxForwardLightFunctions, base + 2), texelFetch(LfxForwardLightFunctions, base + 3));
    return lfx_sample_light_function(LfxForwardLightFunctionAtlas, worldToLight, texelFetch(LfxForwardLightFunctions, base + 4),
            texelFetch(LfxForwardLightFunctions, base + 5), sourcePosition, length(sourcePosition));
}

vec3 lfx_forward_capsule_specular(LfxPointAreaLight area, vec3 specularColor, float roughness, vec3 normal, vec3 view) {
    if (area.normalDotLight <= 0.0) return vec3(0.0);
    return lfx_default_lit_specular(specularColor, roughness, normal, view, area.specularDirection, area.normalDotLight,
            area.sphereSinAlpha, area.sphereSinAlphaSoft, area.lineCosSubtended) * (area.normalDotLight * area.falloff);
}

float lfx_forward_spot_shadow(vec3 toLight, int light) {
    vec4 region = texelFetch(LfxForwardLights, light + 12);
    if (region.w == 0.0) return 1.0;
    mat4 projection = mat4(texelFetch(LfxForwardLights, light + 8), texelFetch(LfxForwardLights, light + 9),
            texelFetch(LfxForwardLights, light + 10), texelFetch(LfxForwardLights, light + 11));
    vec4 clip = projection * vec4(-toLight, 1.0);
    if (clip.w <= 0.0 || clip.z > clip.w) return 1.0;
    int size = int(region.z);
    vec2 uv = lfx_spot_shadow_uv(clip);
    vec2 border = vec2(4.0 / float(size));
    if (any(lessThan(uv, border)) || any(greaterThan(uv, 1.0 - border))) return 1.0;
    vec4 depth = texelFetch(LfxForwardLights, light + 13);
    float receiver = lfx_spot_shadow_depth(clip.w, depth.xy);
    ivec2 origin = ivec2(region.xy);
    ivec2 pixel = origin + clamp(ivec2(uv * float(size)), ivec2(0), ivec2(size - 1));
    float caster = 1.0 - texelFetch(LfxForwardSpotShadowAtlas, pixel, 0).r;
    return caster == 1.0 || receiver < caster - depth.z ? 1.0 : 1.0 - region.w;
}

LfxPointAreaLight lfx_forward_directional_area_light(float roughness, vec3 normal, vec3 lightDirection,
                                                      float sourceRadius, float softSourceRadius) {
    vec3 direction = normalize(lightDirection);
    float radius = clamp(sourceRadius, 0.0, 1.0);
    LfxPointAreaLight area;
    area.diffuseDirection = direction;
    area.specularDirection = direction;
    area.normalDotLight = dot(normal, direction);
    if (radius > 0.0)
        area.normalDotLight = lfx_point_sphere_horizon(area.normalDotLight, radius * radius);
    area.normalDotLight = clamp(area.normalDotLight, 0.0, 1.0);
    area.falloff = 1.0;
    area.lineCosSubtended = 1.0;
    area.sphereSinAlpha = radius * (1.0 - roughness * roughness);
    area.sphereSinAlphaSoft = clamp(softSourceRadius, 0.0, 1.0);
    area.diffuseMicroReflectionWeight = 1.0;
    return area;
}

vec3 lfx_forward_accumulate(LfxPointAreaLight area, vec3 normal, vec3 view, vec3 diffuseColor, vec4 subsurface,
                            float materialAo, float roughness, vec3 radiance, vec3 diffuseRadiance, vec3 specular, vec2 scales,
                            float surfaceShadow, float shadowTransmittance, LfxReflectionEnergy energy) {
#if LFX_LEGACY_ROUGH_DIFFUSE
    float noV = clamp(dot(normal, view), 0.0, 1.0);
    float voL = clamp(dot(view, area.specularDirection), -1.0, 1.0);
    vec3 diffuse = lfx_diffuse_eon(diffuseColor, roughness * area.diffuseMicroReflectionWeight * 0.4,
            noV, area.normalDotLight, voL);
#else
    vec3 diffuse = diffuseColor / LFX_BRDF_PI;
#endif
    diffuse *= area.normalDotLight * energy.diffuseTransmission;
    if (any(greaterThan(subsurface.rgb, vec3(0.0)))) {
        diffuse += lfx_subsurface_wrap_light(subsurface.rgb, false, subsurface.a, clamp(materialAo, 0.0, 1.0),
                normal, view, area.diffuseDirection, shadowTransmittance, ForwardMaterialSettings.x);
    }
    return radiance * (diffuse * diffuseRadiance * scales.x + specular * energy.multiScatter * scales.y) * surfaceShadow;
}

vec3 lfx_forward_accumulate_clear_coat(LfxPointAreaLight area, vec3 diffuseColor, vec3 diffuseWeight,
                                       vec3 radiance, vec3 diffuseRadiance, vec3 specular, vec2 scales,
                                       float surfaceShadow) {
    vec3 diffuse = diffuseColor * diffuseWeight * (area.normalDotLight / LFX_BRDF_PI);
    return radiance * (diffuse * diffuseRadiance * scales.x + specular * scales.y) * surfaceShadow;
}

LfxClearCoatLighting lfx_forward_clear_coat(vec4 custom, vec3 baseColor, float metallic, vec4 f0Roughness,
                                            vec3 bottomNormal, vec3 topNormal, vec3 view, LfxPointAreaLight area
#if LFX_FORWARD_RECT_LIGHTS
                                            , bool isRect, LfxRectLight rect, vec3 sourceRadiance, LfxRectTexture rectTexture
#endif
                                            ) {
    return lfx_evaluate_clear_coat(custom, baseColor, metallic, f0Roughness, bottomNormal, topNormal, view,
            area.specularDirection, area.normalDotLight, area.sphereSinAlpha, area.sphereSinAlphaSoft, area.lineCosSubtended, false,
#if LFX_FORWARD_RECT_LIGHTS
            isRect, rect, mat3(1.0), area.falloff, sourceRadiance, rectTexture,
#endif
            vec4(0.0));
}

vec3 lfx_forward_translucency_impl(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                                   vec3 specularColor, float roughness, vec4 subsurface, float materialAo,
                                   vec3 bentNormal, bool authoredBentNormal, bool clearCoatModel,
                                   float clearCoat, float clearCoatRoughness, float metallic,
                                   vec3 bottomNormal, vec3 unityCoat) {
    int cellIndex = lfx_forward_grid_cell(position, ForwardGridShape, ForwardGridZ, ForwardWorldToView);
    uvec2 cell = texelFetch(LfxForwardLightHeaders, cellIndex).rg;
    vec3 sourcePosition = position.xzy * 100.0;
    roughness = clamp(roughness, 0.0, clearCoatModel ? 254.0 / 255.0 : 1.0);
    vec3 coatBottomNormal = normal;
#ifdef LFX_CLEAR_COAT_BOTTOM_NORMAL
    if (clearCoatModel) coatBottomNormal = normalize(bottomNormal);
#endif
    vec3 areaNormal = clearCoatModel ? coatBottomNormal : normal;
    bool unityCoatModel = unityCoat.x >= 0.0;
    vec4 unityBase = vec4(specularColor, roughness);
    if (unityCoatModel) unityBase = lfx_unity_coat_base(unityBase, unityCoat.x);
    vec3 result = unityCoatModel
            ? lfx_unity_coat_environment(position, normal, view, baseColor, diffuseColor, unityBase, unityCoat, materialAo)
            : lfx_translucent_surface_environment(position, normal, view, baseColor, diffuseColor,
                    specularColor, roughness, subsurface.rgb, materialAo, bentNormal, authoredBentNormal);
    LfxReflectionEnergy energy = lfx_legacy_reflection_energy(roughness,
            clamp(abs(dot(normal, view)) + 1e-5, 0.0, 1.0), specularColor);
    if (ForwardDirectionalFlags.y != 0 && (ForwardDirectionalFlags.x & ForwardPrimitiveChannels.x) != 0) {
        vec3 direction = ForwardDirectionalDirection.xyz;
        LfxPointAreaLight area = lfx_forward_directional_area_light(roughness, areaNormal, direction,
                ForwardDirectionalDirection.w, ForwardDirectionalColor.w);
        vec3 specular;
        float depth = -(ForwardWorldToView * vec4(position, 1.0)).z;
        float staticShadow = lfx_forward_directional_static(position);
        float dynamicShadow = lfx_forward_directional_shadow(position, depth);
        float fade = clamp(depth * ForwardShadowFade.x + ForwardShadowFade.y, 0.0, 1.0);
        float shadow = mix(dynamicShadow, staticShadow, fade * fade);
        vec3 radiance = ForwardDirectionalColor.rgb * lfx_forward_atmosphere_transmittance(sourcePosition);
        if (ForwardMaterialSettings.y >= 0.0)
            radiance *= lfx_forward_light_function(int(ForwardMaterialSettings.y), sourcePosition);
        if (unityCoatModel) {
            result += lfx_unity_coat_direct(diffuseColor, unityBase, unityCoat.xy, normal, view,
                    area.specularDirection, ForwardDirectionalScales) * radiance * (area.normalDotLight * area.falloff * shadow);
        } else if (clearCoatModel) {
            LfxClearCoatLighting coat = lfx_forward_clear_coat(vec4(clearCoat, clearCoatRoughness, 0.0, 0.0),
                    baseColor, metallic, vec4(specularColor, roughness), coatBottomNormal, normal, view, area
#if LFX_FORWARD_RECT_LIGHTS
                    , false, LfxRectLight(vec3(0.0), mat3(1.0), vec2(0.0), vec2(0.0), vec2(0.0)),
                    vec3(0.0), LfxRectTexture(vec4(0.0), 32.0)
#endif
                    );
            specular = coat.specular * (area.normalDotLight * area.falloff);
            result += lfx_forward_accumulate_clear_coat(area, diffuseColor, coat.diffuseWeight, radiance,
                    vec3(area.falloff), specular, ForwardDirectionalScales, shadow);
        } else {
            specular = lfx_forward_capsule_specular(area, specularColor, roughness, normal, view);
            result += lfx_forward_accumulate(area, normal, view, diffuseColor, subsurface, materialAo, roughness,
                    radiance, vec3(area.falloff), specular, ForwardDirectionalScales, shadow, dynamicShadow, energy);
        }
    }
    for (uint index = 0u; index < cell.y; index++) {
        int light = int(texelFetch(LfxForwardLightIndices, int(cell.x + index)).r) * 14;
        vec4 scalesAndFlags = texelFetch(LfxForwardLights, light + 2);
        if ((int(scalesAndFlags.z) & ForwardPrimitiveChannels.x) == 0) continue;
        vec4 positionRadius = texelFetch(LfxForwardLights, light);
        vec4 colorExponent = texelFetch(LfxForwardLights, light + 1);
        vec3 toLight = positionRadius.xyz - sourcePosition;
        float distanceSquared = dot(toLight, toLight);
        if (distanceSquared == 0.0 || distanceSquared * positionRadius.w * positionRadius.w >= 1.0) continue;
        vec4 directionRadius = texelFetch(LfxForwardLights, light + 3);
        vec4 tangentLength = texelFetch(LfxForwardLights, light + 4);
        vec4 shape = texelFetch(LfxForwardLights, light + 5);
        vec4 features = texelFetch(LfxForwardLights, light + 6);
        bool inverseSquare = scalesAndFlags.w != 0.0;
        float attenuation = lfx_point_radial_mask(toLight, positionRadius.w, colorExponent.w, inverseSquare);
        if (shape.y > -1.0)
            attenuation *= lfx_spot_attenuation(toLight * inversesqrt(distanceSquared), directionRadius.xyz, shape.yz);
        vec3 radiance = colorExponent.rgb * attenuation;
        if (features.x >= 0.0) radiance *= lfx_forward_light_function(int(features.x), sourcePosition);
        radiance *= lfx_light_profile(LfxForwardLightProfiles, features.y, sourcePosition,
                positionRadius.xyz, -directionRadius.xyz, tangentLength.xyz);
        if (shape.y > -2.0) radiance *= lfx_forward_spot_shadow(toLight, light);
        else {
            int shadowSlot = int(texelFetch(LfxForwardLights, light + 13).x);
            if (shadowSlot >= 0) {
                float normalDotLight = clamp(dot(normal, toLight * inversesqrt(distanceSquared)), 0.0, 1.0);
                radiance *= lfx_forward_point_shadow(shadowSlot, sourcePosition, positionRadius.xyz,
                        positionRadius.w, normalDotLight);
            }
        }
        LfxPointAreaLight area;
        vec3 diffuseRadiance;
        vec3 specular;
#if LFX_FORWARD_RECT_LIGHTS
        if (shape.y < -2.5 || features.z > 0.0) {
            LfxRectLight rect = lfx_rect_light(toLight, directionRadius.xyz, tangentLength.xyz,
                    vec4(directionRadius.w, tangentLength.w, features.zw));
            if (any(lessThanEqual(rect.extent, vec2(0.0)))) continue;
            LfxRectTexture source = LfxRectTexture(texelFetch(LfxForwardLights, light + 7), shape.w);
            area = lfx_create_rect_area_light(rect, areaNormal);
            vec3 sourceRadiance = lfx_rect_source_radiance(rect, area.diffuseDirection, source);
            diffuseRadiance = sourceRadiance * area.falloff;
            if (unityCoatModel) {
                result += lfx_unity_coat_direct(diffuseColor, unityBase, unityCoat.xy, normal, view,
                        area.diffuseDirection, scalesAndFlags.xy) * radiance * diffuseRadiance * area.normalDotLight;
                continue;
            }
            if (clearCoatModel) {
                LfxClearCoatLighting coat = lfx_forward_clear_coat(vec4(clearCoat, clearCoatRoughness, 0.0, 0.0),
                        baseColor, metallic, vec4(specularColor, roughness), coatBottomNormal, normal, view, area,
                        true, rect, sourceRadiance, source);
                result += lfx_forward_accumulate_clear_coat(area, diffuseColor, coat.diffuseWeight, radiance,
                        diffuseRadiance, coat.specular, scalesAndFlags.xy, 1.0);
                continue;
            }
            specular = area.normalDotLight > 0.0
                    ? lfx_rect_legacy_ggx(rect, normal, view, roughness, specularColor, source) : vec3(0.0);
        } else
#endif
        {
            LfxPointCapsule capsule = lfx_point_capsule(toLight, tangentLength.xyz, tangentLength.w, directionRadius.w, shape.x);
            area = lfx_create_point_area_light(roughness, areaNormal, view, capsule, inverseSquare);
            diffuseRadiance = vec3(area.falloff);
            if (unityCoatModel) {
                result += lfx_unity_coat_direct(diffuseColor, unityBase, unityCoat.xy, normal, view,
                        area.specularDirection, scalesAndFlags.xy) * radiance * (area.normalDotLight * area.falloff);
                continue;
            }
            if (clearCoatModel) {
                LfxClearCoatLighting coat = lfx_forward_clear_coat(vec4(clearCoat, clearCoatRoughness, 0.0, 0.0),
                        baseColor, metallic, vec4(specularColor, roughness), coatBottomNormal, normal, view, area
#if LFX_FORWARD_RECT_LIGHTS
                        , false, LfxRectLight(vec3(0.0), mat3(1.0), vec2(0.0), vec2(0.0), vec2(0.0)),
                        vec3(0.0), LfxRectTexture(vec4(0.0), 32.0)
#endif
                        );
                specular = coat.specular * (area.normalDotLight * area.falloff);
                result += lfx_forward_accumulate_clear_coat(area, diffuseColor, coat.diffuseWeight, radiance,
                        diffuseRadiance, specular, scalesAndFlags.xy, 1.0);
                continue;
            }
            specular = lfx_forward_capsule_specular(area, specularColor, roughness, normal, view);
        }
        result += lfx_forward_accumulate(area, normal, view, diffuseColor, subsurface, materialAo, roughness,
                radiance, diffuseRadiance, specular, scalesAndFlags.xy, 1.0, 1.0, energy);
    }
    return result;
}

vec3 lfx_forward_translucency(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                              vec3 specularColor, float roughness, vec4 subsurface, float materialAo,
                              vec3 bentNormal, bool authoredBentNormal) {
    return lfx_forward_translucency_impl(position, normal, view, baseColor, diffuseColor, specularColor,
            roughness, subsurface, materialAo, bentNormal, authoredBentNormal, false,
            0.0, 0.1, 0.0, normal, vec3(-1.0));
}

vec3 lfx_forward_translucency(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                              vec3 specularColor, float roughness, vec4 subsurface, float materialAo,
                              vec3 bentNormal, bool authoredBentNormal, float clearCoat, float clearCoatRoughness,
                              float metallic, vec3 bottomNormal) {
    return lfx_forward_translucency_impl(position, normal, view, baseColor, diffuseColor, specularColor,
            roughness, subsurface, materialAo, bentNormal, authoredBentNormal, true,
            clearCoat, clearCoatRoughness, metallic, bottomNormal, vec3(-1.0));
}

vec3 lfx_forward_unity_coat(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                            vec3 specularColor, float roughness, float materialAo, vec3 coat) {
    return lfx_forward_translucency_impl(position, normal, view, baseColor, diffuseColor, specularColor,
            roughness, vec4(0.0), materialAo, normal, false, false, 0.0, 0.0, 0.0, normal, coat);
}

vec3 lfx_forward_translucency(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                              vec3 specularColor, float roughness, vec4 subsurface, float materialAo) {
    return lfx_forward_translucency(position, normal, view, baseColor, diffuseColor, specularColor,
            roughness, subsurface, materialAo, normal, false);
}

vec3 lfx_forward_translucency(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                              vec3 specularColor, float roughness, vec4 subsurface, float materialAo,
                              float clearCoat, float clearCoatRoughness, float metallic, vec3 bottomNormal) {
    return lfx_forward_translucency(position, normal, view, baseColor, diffuseColor, specularColor,
            roughness, subsurface, materialAo, normal, false, clearCoat, clearCoatRoughness, metallic, bottomNormal);
}

vec3 lfx_forward_translucency(vec3 position, vec3 normal, vec3 view, vec3 diffuseColor,
                              vec3 specularColor, float roughness, vec4 subsurface, float materialAo) {
    return lfx_forward_translucency(position, normal, view, diffuseColor, diffuseColor, specularColor,
            roughness, subsurface, materialAo, normal, false);
}
