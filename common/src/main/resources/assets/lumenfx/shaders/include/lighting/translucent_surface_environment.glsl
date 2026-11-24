#moj_import <lumenfx:lighting/scene_indirect_diffuse.glsl>
#moj_import <lumenfx:lighting/material_bent_normal.glsl>
#moj_import <lumenfx:lighting/forward_grid_cell.glsl>
#moj_import <lumenfx:lighting/unity_clear_coat.glsl>
#ifndef LFX_MATERIAL_PREINTEGRATED_GF
#define LFX_MATERIAL_PREINTEGRATED_GF 1
#endif
#ifndef LFX_FORWARD_BLEND_LOCAL_CAPTURES
#define LFX_FORWARD_BLEND_LOCAL_CAPTURES 1
#endif
#if LFX_FORWARD_SKY_REFLECTION || LFX_FORWARD_LOCAL_CAPTURES
#moj_import <lumenfx:lighting/reflection_mips.glsl>
#moj_import <lumenfx:lighting/reflection_brdf.glsl>
#endif
#if LFX_FORWARD_SKY_REFLECTION
#moj_import <lumenfx:lighting/sky_reflection.glsl>
#endif
#if LFX_FORWARD_LOCAL_CAPTURES
#moj_import <lumenfx:lighting/local_reflections.glsl>
#if LFX_FORWARD_BLEND_LOCAL_CAPTURES
layout(std140) uniform LfxForwardCaptureGrid {
    ivec4 ForwardCaptureShape;
    vec4 ForwardCaptureZ;
    mat4 ForwardCaptureWorldToView;
};
uniform usamplerBuffer LfxForwardCaptureHeaders;
#else
layout(std140) uniform LfxForwardPrimitiveCapture {
    ivec4 ForwardPrimitiveReflection;
};
#endif
#endif

#if LFX_FORWARD_LOCAL_CAPTURES
vec3 lfx_translucent_local_reflection(vec3 position, vec3 ray, float roughness, vec3 irradiance) {
    vec3 sky = vec3(0.0);
    float skyAverage = 0.0;
    bool normalizeSky = false;
#if LFX_FORWARD_SKY_REFLECTION
    sky = lfx_sky_capture_sample(ray, roughness);
    skyAverage = ForwardSkyReflectionLuminance.w;
    normalizeSky = LfxSkyReflectionFlags.x > 0.0;
#endif
    float indirectBrightness = dot(irradiance, LocalReflectionLuminance.rgb);
#if LFX_FORWARD_BLEND_LOCAL_CAPTURES
    int cell = lfx_forward_grid_cell(position, ForwardCaptureShape, ForwardCaptureZ, ForwardCaptureWorldToView);
    ivec2 selection = ivec2(texelFetch(LfxForwardCaptureHeaders, cell).rg);
    return lfx_composite_local_reflections(position.xzy * 100.0, ray, roughness, indirectBrightness,
            1.0, sky, skyAverage, normalizeSky, selection);
#else
    return lfx_composite_single_local_reflection(ForwardPrimitiveReflection.x, ray, roughness, indirectBrightness,
            sky, normalizeSky);
#endif
}
#endif

vec3 lfx_translucent_surface_environment(vec3 position, vec3 normal, vec3 view, vec3 baseColor, vec3 diffuseColor,
                                         vec3 specularColor, float roughness, vec3 subsurface, float materialAo,
                                         vec3 bentNormal, bool authoredBentNormal) {
    roughness = clamp(roughness, 0.0, 1.0);
    LfxShadingOcclusion occlusion = lfx_material_shading_occlusion(normal, bentNormal, view, roughness, materialAo, authoredBentNormal);
    vec3 precomputedIrradiance;
    vec3 result = lfx_scene_indirect_diffuse(position, occlusion.bentNormal, baseColor, diffuseColor + subsurface,
            occlusion.diffuse, false, vec3(0.0), precomputedIrradiance);
#if LFX_FORWARD_LOCAL_CAPTURES
    float noV = clamp(dot(normal, view), 0.0, 1.0);
    vec3 ray = reflect(-view, normal);
    vec3 environment = lfx_translucent_local_reflection(position, ray, roughness, precomputedIrradiance);
#if LFX_MATERIAL_PREINTEGRATED_GF
    vec2 gf = textureLod(LfxLocalReflectionGF, vec2(noV, roughness), 0.0).rg;
#else
    vec2 gf = lfx_environment_brdf_analytic(roughness, noV);
#endif
    result += environment * lfx_environment_brdf(gf, specularColor) * lfx_indirect_ao_multibounce(specularColor, occlusion.specular);
#elif LFX_FORWARD_SKY_REFLECTION
    result += lfx_forward_sky_reflection(normal, view, specularColor, roughness, occlusion.specular, precomputedIrradiance);
#endif
    return result;
}

vec3 lfx_unity_coat_environment(vec3 position, vec3 normal, vec3 view, vec3 albedo,
                                vec3 diffuse, vec4 base, vec3 coat, float ao) {
    vec3 irradiance;
    vec3 color = lfx_scene_indirect_diffuse(position, normal, albedo, diffuse, 1.0, false, vec3(0.0), irradiance);
    vec3 baseRadiance = vec3(0.0), coatRadiance = vec3(0.0);
    vec3 ray = reflect(-view, normal);
#if LFX_FORWARD_LOCAL_CAPTURES
    baseRadiance = lfx_translucent_local_reflection(position, ray, base.w, irradiance);
    if (coat.x > 0.0) coatRadiance = lfx_translucent_local_reflection(position, ray, coat.y, irradiance);
#elif LFX_FORWARD_SKY_REFLECTION
    float brightness = dot(irradiance, ForwardSkyReflectionLuminance.xyz);
    baseRadiance = lfx_sky_radiance(ray, base.w, brightness);
    if (coat.x > 0.0) coatRadiance = lfx_sky_radiance(ray, coat.y, brightness);
#endif
    float fresnel = lfx_unity_coat_fresnel_power(dot(normal, view));
    color += baseRadiance * lfx_unity_coat_environment_brdf(base, coat.z, fresnel);
    vec3 top = coatRadiance * lfx_unity_coat_environment_brdf(vec4(vec3(0.04), coat.y),
            clamp(1.04 - coat.y, 0.0, 1.0), fresnel);
    return (color * lfx_unity_coat_transmission(coat.x, fresnel) + top * coat.x) * ao;
}
