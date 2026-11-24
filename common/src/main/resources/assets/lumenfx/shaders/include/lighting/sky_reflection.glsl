layout(std140) uniform LfxForwardSkyReflection {
    vec4 ForwardSkyReflectionColor;
    vec4 ForwardSkyReflectionMix;
    vec4 ForwardSkyReflectionLuminance;
    vec4 LfxSkyReflectionFlags;
};
uniform samplerCube LfxForwardSkyCube;
#if LFX_FORWARD_SKY_BLEND || LFX_DEFERRED_SKY_BLEND
uniform samplerCube LfxSkyBlendDestination;
#endif
#if LFX_FORWARD_PREINTEGRATED_GF || LFX_DEFERRED_SKY_PREINTEGRATED_GF
uniform sampler2D LfxForwardPreintegratedGF;
#endif

vec3 lfx_sky_capture_sample(vec3 ray, float roughness) {
    float mip = lfx_reflection_mip(roughness, ForwardSkyReflectionColor.w);
    vec3 sky = textureLod(LfxForwardSkyCube, ray, mip).rgb;
#if LFX_DEFERRED_SKY_BLEND || (LFX_FORWARD_SKY_BLEND && (LFX_MATERIAL_SKY_BLEND || LFX_FORCE_FORWARD_SKY_BLEND))
    sky = mix(sky, textureLod(LfxSkyBlendDestination, ray, mip).rgb, ForwardSkyReflectionMix.w);
#endif
    return sky * ForwardSkyReflectionColor.rgb;
}

vec3 lfx_sky_radiance(vec3 ray, float roughness, float indirectBrightness) {
    float mixWeight = indirectBrightness / max(ForwardSkyReflectionLuminance.w, 0.0001);
    float alpha = smoothstep(0.0, 1.0, clamp(roughness * ForwardSkyReflectionMix.x + ForwardSkyReflectionMix.y, 0.0, 1.0));
    return lfx_sky_capture_sample(ray, roughness) * mix(1.0, min(mixWeight, ForwardSkyReflectionMix.z), alpha);
}

vec2 lfx_sky_brdf_terms(float roughness, float noV) {
#if (LFX_FORWARD_PREINTEGRATED_GF && LFX_MATERIAL_PREINTEGRATED_GF) || LFX_DEFERRED_SKY_PREINTEGRATED_GF
    return textureLod(LfxForwardPreintegratedGF, vec2(noV, roughness), 0.0).rg;
#else
    return lfx_environment_brdf_analytic(roughness, noV);
#endif
}

vec3 lfx_forward_sky_reflection(vec3 normal, vec3 view, vec3 f0, float roughness, float specularOcclusion,
                                vec3 precomputedIrradiance) {
    float noV = clamp(dot(normal, view), 0.0, 1.0);
    vec3 ray = 2.0 * dot(view, normal) * normal - view;
    vec3 sky = lfx_sky_radiance(ray, roughness, dot(precomputedIrradiance, ForwardSkyReflectionLuminance.xyz));
    vec2 gf = lfx_sky_brdf_terms(roughness, noV);
    vec3 brdf = lfx_environment_brdf(gf, f0);
    return sky * brdf * lfx_indirect_ao_multibounce(f0, specularOcclusion);
}
