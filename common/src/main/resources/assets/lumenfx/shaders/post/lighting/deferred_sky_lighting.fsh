#version 330

#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:lighting/default_lit_brdf.glsl>
#moj_import <lumenfx:lighting/scene_indirect_diffuse.glsl>
#moj_import <lumenfx:lighting/reflection_energy.glsl>
#moj_import <lumenfx:lighting/unity_clear_coat.glsl>
#if LFX_DEFERRED_SKY_REFLECTION
#moj_import <lumenfx:lighting/reflection_mips.glsl>
#moj_import <lumenfx:lighting/reflection_brdf.glsl>
#endif
#if LFX_DEFERRED_SKY_TOON
#moj_import <lumenfx:substrate/toon_profile.glsl>
    #if LFX_DEFERRED_TOON_REFLECTION_EFFECTS
    #moj_import <lumenfx:substrate/toon_indirect_reflection.glsl>
    #endif
#endif
#if LFX_DEFERRED_SKY_CUBEMAP
#moj_import <lumenfx:lighting/sky_reflection.glsl>
#endif
#if LFX_DEFERRED_LOCAL_CAPTURES
#moj_import <lumenfx:lighting/local_reflections.glsl>
#endif
#if LFX_DEFERRED_SKY_CUSTOM
#moj_import <lumenfx:lighting/clear_coat_normals.glsl>
#moj_import <lumenfx:lighting/legacy_hair_brdf.glsl>
uniform sampler2D SkyParticleCustom;
#endif
#if LFX_DEFERRED_SKY_SOURCE
uniform sampler2D SkyParticleSource;
#endif
#if LFX_DEFERRED_SKY_ANISOTROPY
uniform sampler2D SkyParticleAnisotropy;
#endif

layout(std140) uniform LfxDeferredSkyView {
    mat4 SkyProjection;
    mat4 SkyViewToWorld;
    vec4 SkyOcclusionTint;
};
uniform sampler2D SkyParticleAlbedo;
uniform sampler2D SkyParticleNormal;
uniform sampler2D SkyParticleGeometry;
uniform sampler2D SkyParticleLighting;
uniform sampler2D SkySceneDepth;
#if LFX_DEFERRED_SKY_AO
uniform sampler2D SkySceneAO;
#endif

layout(location = 0) out vec4 fragColor;
#if LFX_DEFERRED_SKY_SEPARATE_DIFFUSE
layout(location = 1) out vec4 profileDiffuse;
#endif

#if LFX_DEFERRED_SKY_REFLECTION
vec2 lfx_deferred_brdf_terms(float roughness, float noV) {
#if LFX_DEFERRED_LOCAL_CAPTURES
    return textureLod(LfxLocalReflectionGF, vec2(noV, roughness), 0.0).rg;
#else
    return lfx_sky_brdf_terms(roughness, noV);
#endif
}

vec3 lfx_deferred_environment(vec3 position, vec3 normal, vec3 ray, float roughness, float indirectBrightness,
                              float specularOcclusion) {
#if LFX_DEFERRED_LOCAL_CAPTURES
    vec3 sky = vec3(0.0);
    float skyAverage = 0.0;
#if LFX_DEFERRED_SKY_CUBEMAP
    sky = lfx_sky_capture_sample(ray, roughness);
    skyAverage = ForwardSkyReflectionLuminance.w;
    #if !LFX_DEFERRED_SKY_NORMALIZE && LFX_DEFERRED_SKY_STATIC_LIGHTING
    indirectBrightness += dot(lfx_forward_sky_diffuse(normal), LocalReflectionLuminance.rgb);
    #endif
#endif
    return lfx_composite_local_reflections(position, ray, roughness, indirectBrightness, specularOcclusion,
            sky, skyAverage, LFX_DEFERRED_SKY_NORMALIZE != 0);
#else
    return specularOcclusion * lfx_sky_radiance(ray, roughness, indirectBrightness);
#endif
}

vec3 lfx_deferred_sky_ray(vec3 normal, vec3 view, float roughness) {
    float a = roughness * roughness;
    vec3 reflection = 2.0 * dot(view, normal) * normal - view;
    return mix(normal, reflection, (1.0 - a) * (sqrt(1.0 - a) + a));
}

vec3 lfx_deferred_sky_albedo(vec3 f0, float roughness, float noV) {
    float peak = max(f0.r, max(f0.g, f0.b));
    vec3 f90 = vec3(clamp(50.0 * peak, 0.0, 1.0));
    return lfx_ggx_reflection_energy(roughness, noV, f0, f90, LFX_LEGACY_ENERGY_CONSERVE != 0,
            false, true).directionalAlbedo;
}
#endif

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 geometry = texelFetch(SkyParticleGeometry, pixel, 0);
    if (!lfx_surface_has_default_lit_particle(geometry) || geometry.w != texelFetch(SkySceneDepth, pixel, 0).r) discard;
    int model = lfx_surface_particle_shading_model(geometry);
    if (model >= LFX_SHADING_MODEL_SUBSTRATE && model != LFX_SHADING_MODEL_SUBSTRATE_TOON) discard;
    bool unityCoat = lfx_surface_uses_unity_coat(geometry);

    vec4 normalAO = texelFetch(SkyParticleNormal, pixel, 0);
    vec3 viewPosition = lfx_surface_view_position(geometry, SkyProjection, LFX_DEFERRED_SKY_ZERO_TO_ONE != 0);
    mat3 viewToWorld = mat3(SkyViewToWorld);
    vec3 normal = normalize(viewToWorld * normalAO.xyz).xzy;
    vec3 view = normalize(viewToWorld * -viewPosition).xzy;
    if (unityCoat && SkyProjection[3][3] >= 1.0) view = normalize(viewToWorld[2].xyz).xzy;
    vec3 lookupNormal = normal;
    vec4 specular = texelFetch(SkyParticleLighting, pixel, 0);
    vec3 originalF0 = specular.rgb;
    vec3 diffuse = srgbToLinear(texelFetch(SkyParticleAlbedo, pixel, 0).rgb);
    float noV = clamp(dot(normal, view), 0.0, 1.0);
    // The legacy static-lighting GBuffer decodes AO as one because its slot stores the irradiance product.
    float materialOcclusion = LFX_DEFERRED_SKY_STATIC_LIGHTING != 0 && !unityCoat ? 1.0 : clamp(normalAO.w, 0.0, 1.0);
    float screenOcclusion = 1.0;
#if LFX_DEFERRED_SKY_AO
    screenOcclusion = texelFetch(SkySceneAO, pixel, 0).r;
#endif
#if LFX_DEFERRED_SKY_TOON
    if (model == LFX_SHADING_MODEL_SUBSTRATE_TOON) {
        vec4 toonSurface = texelFetch(SkyParticleCustom, pixel, 0);
        int profile = lfx_toon_profile_layer(toonSurface);
        vec4 settings = lfx_toon_settings(profile, 3);
        if (SkyProjection[3][3] >= 1.0) view = normalize(viewToWorld[2].xyz).xzy;
        float visibility = min(clamp(normalAO.w, 0.0, 1.0), screenOcclusion);
        vec3 color = vec3(0.0);
#if LFX_DEFERRED_SKY_DIFFUSE
        vec3 lookup = visibility * lfx_forward_sky_diffuse(normal) + (1.0 - visibility) * SkyOcclusionTint.rgb;
        color = lookup * diffuse * settings.x;
#endif
#if LFX_DEFERRED_SKY_REFLECTION
        float roughness = max(specular.a, 0.02);
        float noV = clamp(dot(normal, view), 0.0, 1.0);
        vec3 ray = lfx_deferred_sky_ray(normal, view, roughness);
        vec3 sourcePosition = (SkyViewToWorld * vec4(viewPosition, 1.0)).xzy * 100.0;
        float ao = clamp(normalAO.w, 0.0, 1.0) * screenOcclusion;
        float specularOcclusion = clamp(pow(max(noV + ao, 1e-20), roughness * roughness) - 1.0 + ao, 0.0, 1.0);
        vec2 gf = lfx_environment_brdf_analytic(roughness, noV);
        vec3 reflected = lfx_deferred_environment(sourcePosition, normal, ray, roughness, 1.0, specularOcclusion)
                * lfx_environment_brdf(gf, specular.rgb) * settings.y;
#if LFX_DEFERRED_TOON_REFLECTION_EFFECTS
        reflected = lfx_toon_indirect_ramp(profile, reflected, settings.z);
        reflected = lfx_toon_indirect_texture(profile, reflected, int(settings.w), normal, view, viewToWorld[1].xyz.xzy);
#endif
        color += reflected;
#endif
        fragColor = vec4(color * toonSurface.z, 0.0);
#if LFX_DEFERRED_SKY_SEPARATE_DIFFUSE
        profileDiffuse = vec4(0.0);
#endif
        return;
    }
#endif
    float skyVisibility = min(materialOcclusion, screenOcclusion);
    vec3 scattered = vec3(0.0);
    bool hair = false;
    vec4 custom = vec4(0.0);
#if LFX_DEFERRED_SKY_SOURCE
    vec4 source = texelFetch(SkyParticleSource, pixel, 0);
    uint sourceBits = uint(source.a);
#endif
#if LFX_DEFERRED_SKY_CUSTOM
    custom = texelFetch(SkyParticleCustom, pixel, 0);
    vec3 subsurface = custom.rgb * custom.rgb;
    if (model == LFX_SHADING_MODEL_CLEAR_COAT && !unityCoat) {
        float metallic = float(sourceBits & 255u) / 255.0;
        float authoredSpecular = float((sourceBits >> 8) & 255u) / 255.0;
        float refraction = 1.0 + ((0.5 * noV + 0.5) * noV - 1.0) * clamp(1.25 - 1.25 * specular.a, 0.0, 1.0);
        vec3 absorption = source.rgb / 0.9;
        absorption *= 1.0 + (noV - 1.0) * 0.85 * (1.0 - mix(absorption, absorption * absorption, -0.78));
        float fresnel = 0.04 + 0.96 * pow(1.0 - noV, 5.0);
        vec3 base = mix(source.rgb * (1.0 - fresnel * custom.x), 0.9 * absorption * refraction, metallic * custom.x);
        diffuse = base * (1.0 - metallic);
        specular.rgb = mix(vec3(0.08 * mix(authoredSpecular, refraction, custom.x)), base, metallic);
#if LFX_DEFERRED_SKY_BOTTOM_NORMAL
        lookupNormal = lfx_clear_coat_unit_vector(lfx_clear_coat_octahedron(normal) + custom.wz * 4.0 - 512.0 / 255.0);
#endif
    } else if (model == LFX_SHADING_MODEL_TWOSIDED_FOLIAGE) {
        scattered = skyVisibility * lfx_forward_sky_diffuse(-normal) * subsurface;
    } else if (model == LFX_SHADING_MODEL_SUBSURFACE || model == LFX_SHADING_MODEL_PREINTEGRATED_SKIN) {
        diffuse += subsurface;
    } else if (model == LFX_SHADING_MODEL_CLOTH) {
        diffuse += subsurface * custom.a;
    } else if (model == LFX_SHADING_MODEL_HAIR) {
        diffuse = lfx_hair_environment(source.rgb, float(sourceBits & 255u) / 255.0,
                float((sourceBits >> 8) & 255u) / 255.0, specular.a, normal, view, lookupNormal);
        hair = true;
    }
#if LFX_DEFERRED_SKY_SEPARATE_DIFFUSE
    if (lfx_surface_uses_subsurface_profile(model)) {
        diffuse = vec3(model == LFX_SHADING_MODEL_EYE ? 1.0 : 1.0 - float(sourceBits & 255u) / 255.0);
    }
#endif
#endif
#if LFX_DEFERRED_SKY_DIFFUSE
    float preservation = unityCoat ? lfx_unity_coat_transmission(custom.x, lfx_unity_coat_fresnel_power(noV))
            : hair ? 1.0 : lfx_legacy_reflection_energy(specular.a, noV, originalF0).diffuseTransmission;
    vec3 lookup = skyVisibility * lfx_forward_sky_diffuse(lookupNormal) + (1.0 - skyVisibility) * SkyOcclusionTint.rgb;
    vec3 color = scattered + lookup * diffuse * preservation;
#else
    vec3 color = vec3(0.0);
#endif
    vec3 environment = vec3(0.0);
#if LFX_DEFERRED_SKY_REFLECTION
    if (model != LFX_SHADING_MODEL_HAIR) {
        float roughness = specular.a;
        vec3 specularNormal = normal;
#if LFX_DEFERRED_SKY_ANISOTROPY
        if (lfx_surface_has_anisotropic_particle(geometry)) {
            vec4 tangent = texelFetch(SkyParticleAnisotropy, pixel, 0) * 2.0 - 1.0;
            vec3 tangentDirection = tangent.w >= 0.0 ? normalize(cross(normal, tangent.xyz)) : tangent.xyz;
            vec3 stretched = cross(cross(tangentDirection, view), tangentDirection);
            specularNormal = normalize(mix(normal, stretched, abs(tangent.w) * clamp(5.0 * roughness, 0.0, 1.0)));
        }
#endif
        float specularNoV = clamp(dot(specularNormal, view), 0.0, 1.0);
        vec3 ray = unityCoat ? reflect(-view, normal) : lfx_deferred_sky_ray(specularNormal, view, roughness);
        bool coat = model == LFX_SHADING_MODEL_CLEAR_COAT && !unityCoat;
#if LFX_DEFERRED_SKY_BOTTOM_NORMAL
        if (coat) ray = 2.0 * dot(view, lookupNormal) * lookupNormal - view;
#endif
        float indirectBrightness = 1.0;
#if LFX_DEFERRED_SKY_STATIC_LIGHTING && LFX_DEFERRED_SKY_SOURCE
        indirectBrightness = exp2(float((sourceBits >> 16) & 255u) * (16.0 / 255.0) - 8.0) - 0.00390625;
#endif
        float ao = materialOcclusion * screenOcclusion;
        float specularOcclusion = unityCoat ? ao
                : clamp(pow(max(specularNoV + ao, 1e-20), roughness * roughness) - 1.0 + ao, 0.0, 1.0);
        vec3 sourcePosition = (SkyViewToWorld * vec4(viewPosition, 1.0)).xzy * 100.0;
        environment = lfx_deferred_environment(sourcePosition, normal, ray, roughness, indirectBrightness, specularOcclusion);
        if (unityCoat) {
            float fresnel = lfx_unity_coat_fresnel_power(noV);
            environment *= lfx_unity_coat_environment_brdf(specular, custom.z, fresnel)
                    * lfx_unity_coat_transmission(custom.x, fresnel);
            if (custom.x > 0.0) {
                vec3 top = lfx_deferred_environment(sourcePosition, normal, ray, custom.y, indirectBrightness, specularOcclusion);
                environment += top * lfx_unity_coat_environment_brdf(vec4(vec3(0.04), custom.y),
                        clamp(1.04 - custom.y, 0.0, 1.0), fresnel) * custom.x;
            }
        } else {
#if LFX_DEFERRED_SKY_REFLECTION_ENERGY
            environment *= lfx_deferred_sky_albedo(originalF0, roughness, specularNoV);
#else
            vec2 gf = lfx_deferred_brdf_terms(roughness, coat ? noV : specularNoV);
            float f90 = clamp(50.0 * specular.g, 0.0, 1.0);
            environment *= specular.rgb * gf.x + gf.y * f90 * (coat ? 1.0 - custom.x : 1.0);
#endif
            if (coat) {
                float coatRoughness = custom.y;
                const float coatF0 = 0.04;
#if LFX_DEFERRED_SKY_REFLECTION_ENERGY
                float fresnel = lfx_deferred_sky_albedo(vec3(coatF0), coatRoughness, noV).r;
#else
                vec2 coatGf = lfx_deferred_brdf_terms(coatRoughness, noV);
                float fresnel = coatF0 * coatGf.x + coatGf.y;
#endif
                fresnel *= custom.x;
                vec3 topRay = 2.0 * dot(view, normal) * normal - view;
                vec3 top = lfx_deferred_environment(sourcePosition, normal, topRay, coatRoughness, indirectBrightness, specularOcclusion);
                environment = environment * (1.0 - fresnel) + top * fresnel;
            }
        }
    }
#endif
#if LFX_DEFERRED_SKY_SEPARATE_DIFFUSE
    bool separate = lfx_surface_uses_subsurface_profile(model);
    profileDiffuse = vec4(separate ? color : vec3(0.0), 0.0);
    fragColor = vec4((separate ? vec3(0.0) : color) + environment, 0.0);
#else
    fragColor = vec4(color + environment, 0.0);
#endif
}
