#moj_import <lumenfx:lighting/unity_brdf_inputs.glsl>

#if LFX_FORWARD_TRANSLUCENCY && !LFX_PARTICLE_MOTION
#moj_import <minecraft:projection.glsl>
#moj_import <lumenfx:lighting/forward_translucency.glsl>

vec3 lfx_unity_particle_view(vec3 position) {
    vec3 viewDirection = vec3(ForwardWorldToView[0].z, ForwardWorldToView[1].z, ForwardWorldToView[2].z);
    if (ProjMat[3][3] < 1.0 && dot(position, position) > 0.0) viewDirection = -position;
    return normalize(viewDirection).xzy;
}

vec3 lfx_unity_coated_forward(vec3 position, vec3 worldNormal, vec3 albedo, float metallic,
                              vec3 specular, bool specularWorkflow, float smoothness, float occlusion,
                              float diffuseAlpha, float coatMask, float coatRoughness) {
    float metal = clamp(metallic, 0.0, 1.0);
    float reflectivity = specularWorkflow ? lfx_unity_specular_reflectivity(specular) : 0.04 + 0.96 * metal;
    vec3 diffuse = albedo * ((1.0 - reflectivity) * diffuseAlpha);
    vec3 f0 = specularWorkflow ? specular : mix(vec3(0.04), albedo, metal);
    float baseSmoothness = clamp(smoothness, 0.0, 1.0);
    float grazing = clamp(baseSmoothness + reflectivity, 0.0, 1.0);
    return lfx_forward_unity_coat(position, normalize(worldNormal).xzy, lfx_unity_particle_view(position), albedo,
            diffuse, f0, 1.0 - baseSmoothness, occlusion, vec3(coatMask, coatRoughness, grazing));
}

vec3 lfx_unity_metallic_forward(vec3 position, vec3 worldNormal, vec3 albedo, float metallic,
                               float smoothness, float occlusion, float diffuseAlpha) {
    return lfx_unity_coated_forward(position, worldNormal, albedo, metallic, vec3(0.0), false,
            smoothness, occlusion, diffuseAlpha, 0.0, 0.0);
}

vec3 lfx_unity_specular_forward(vec3 position, vec3 worldNormal, vec3 albedo, vec3 specular,
                               float smoothness, float occlusion, float diffuseAlpha) {
    return lfx_unity_coated_forward(position, worldNormal, albedo, 0.0, specular, true,
            smoothness, occlusion, diffuseAlpha, 0.0, 0.0);
}
#endif
