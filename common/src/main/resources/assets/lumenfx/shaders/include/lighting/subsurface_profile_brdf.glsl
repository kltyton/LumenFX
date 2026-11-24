#moj_import <lumenfx:lighting/subsurface_profile.glsl>

void lfx_subsurface_profile_light(vec4 material, vec4 profileData, vec3 diffuseColor,
                                 vec3 normal, vec3 view, vec3 light, float noL, vec3 area, float retroreflection,
#ifdef LFX_RECT_LIGHT
                                 LfxRectLight rect, vec3 sourceNormal, vec3 sourceView,
#endif
                                 out vec3 diffuse, out vec3 specular) {
    float rawNoV = dot(normal, view);
    float noV = clamp(abs(rawNoV) + 1e-5, 0.0, 1.0);
    vec2 halfVector = lfx_default_lit_half_vector(rawNoV, dot(normal, light), dot(view, light), area.x, true);

#if LFX_LEGACY_ROUGH_DIFFUSE
    diffuse = lfx_diffuse_eon(diffuseColor, retroreflection * material.w * 0.4, noV, noL,
            2.0 * halfVector.y * halfVector.y - 1.0);
#else
    float fd90 = 0.5 + 2.0 * halfVector.y * halfVector.y * material.w;
    float fdV = 1.0 + (fd90 - 1.0) * pow(1.0 - noV, 5.0);
    float fdL = 1.0 + (fd90 - 1.0) * pow(1.0 - noL, 5.0);
    diffuse = diffuseColor * (fdV * fdL / LFX_BRDF_PI);
#endif

    vec3 dualLobe = lfx_subsurface_profile_load(lfx_subsurface_profile_id(profileData), 5).rgb;
    float strength = clamp((profileData.a - 0.1) * 10.0, 0.0, 1.0);
    vec2 roughness = clamp(material.w * mix(vec2(1.0), dualLobe.xy * 2.0, strength), 0.0, 1.0);
    roughness.x = max(roughness.x, 0.02);
    float average = mix(roughness.x, roughness.y, dualLobe.z);
    LfxReflectionEnergy energy = lfx_legacy_reflection_energy(average, noV, material.rgb);
    diffuse *= energy.diffuseTransmission;
#ifdef LFX_RECT_LIGHT
    vec3 lobe0 = lfx_rect_legacy_ggx(rect, sourceNormal, sourceView, roughness.x, material.rgb);
    vec3 lobe1 = lfx_rect_legacy_ggx(rect, sourceNormal, sourceView, roughness.y, material.rgb);
    specular = mix(lobe0, lobe1, dualLobe.z) * energy.multiScatter;
#else
    float alpha = average * average;
    float visibility = lfx_ggx_joint_visibility(alpha * alpha, noV, noL, false);
    float lobe0 = lfx_ggx_area_distribution(roughness.x, halfVector, area.x, area.y, area.z, false).x;
    float lobe1 = lfx_ggx_area_distribution(roughness.y, halfVector, area.x, area.y, area.z, false).x;
    specular = lfx_default_lit_fresnel(material.rgb, halfVector.y) * energy.multiScatter
            * (visibility * mix(lobe0, lobe1, dualLobe.z));
#endif
}

vec3 lfx_subsurface_profile_transmission(vec4 profileData, float opticalMask,
                                         vec3 normal, vec3 view, vec3 diffuseDirection) {
    return lfx_subsurface_profile_transmission(lfx_subsurface_profile_id(profileData),
            (1.0 - opticalMask) * 5.0, normal, view, diffuseDirection);
}
