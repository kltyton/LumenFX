#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:lighting/clear_coat_normals.glsl>
#moj_import <lumenfx:lighting/unity_brdf_inputs.glsl>
#moj_import <lumenfx:lighting/unity_clear_coat.glsl>

// Mojang's shader loader deduplicates imports before evaluating conditionals.
#moj_import <lumenfx:lighting/material_bent_normal.glsl>
#moj_import <lumenfx:lighting/indirect_occlusion.glsl>
#if LFX_FORWARD_TRANSLUCENCY || (LFX_PARTICLE_SURFACE && LFX_OPAQUE_INDIRECT)
#moj_import <lumenfx:lighting/scene_indirect_diffuse.glsl>
#moj_import <lumenfx:lighting/default_lit_brdf.glsl>
#endif

#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION
#if LFX_OPAQUE_INDIRECT
#moj_import <lumenfx:lighting/legacy_hair_brdf.glsl>
#if LFX_PARTICLE_SUBSTRATE_TOON
#moj_import <lumenfx:substrate/toon_profile.glsl>
#endif
#endif
#if LFX_PARTICLE_SUBSTRATE_HAIR || LFX_PARTICLE_SUBSTRATE_EYE || LFX_PARTICLE_SUBSTRATE_TOON
#moj_import <lumenfx:substrate/surface_packing.glsl>
#endif
#if LFX_PARTICLE_SUBSTRATE_TOON
vec4 lfx_pack_toon_surface(vec2 patternUv, float profile, float coverage) {
    uvec2 packedUv = uvec2(fract(patternUv) * 4095.0);
    return vec4(vec2(packedUv), coverage, float(uint(profile) & 255u));
}
#if LFX_PARTICLE_TOON_ADAPTIVE
#moj_import <lumenfx:math/interleaved_gradient_noise.glsl>
vec3 lfx_toon_color665(vec3 color, float dither) {
    const vec3 scale = vec3(63.0, 63.0, 31.0);
    bool precise = LfxSubstratePackingView.y >= 1;
    vec3 encoded = precise ? linearToSrgb(color) : lfx_substrate_sqrt_fast(color);
    vec3 quantized = clamp(encoded + (dither - 0.5) * vec3(notEqual(encoded, vec3(0.0))) / scale, 0.0, 1.0);
    encoded = vec3(uvec3(quantized * scale)) / scale;
    return precise ? srgbToLinear(encoded) : encoded * encoded;
}

float lfx_toon_scalar5(float value, float dither) {
    float quantized = clamp(value + (dither - 0.5) * float(value != 0.0) / 31.0, 0.0, 1.0);
    return float(uint(quantized * 31.0)) / 31.0;
}
#endif
#endif
#if LFX_PARTICLE_SUBSTRATE_EYE
float lfx_pack_eye_normal(vec3 direction) {
    vec2 oct = lfx_clear_coat_octahedron(normalize(direction)) * 0.5 + 0.5;
    return float(lfx_substrate_pack8(oct.x) | (lfx_substrate_pack8(oct.y) << 8));
}
#endif
void lfx_write_lit_surface(vec3 base, float metallic, float specular, float roughness,
                           float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                           float anisotropy, vec3 worldTangent, int shadingModel, vec4 customData, vec3 bottomNormal,
                           vec3 eyePlane, float irisDistance, int profileIndex, vec3 authoredBentNormal, bool hasBentNormal,
                           vec3 specularColor, bool unitySpecularWorkflow, bool unityClearCoat, bool unityUrpLit) {
    base = clamp(base, vec3(0.0), vec3(1.0));
    metallic = clamp(metallic, 0.0, 1.0);
    specular = clamp(specular, 0.0, 1.0);
    roughness = clamp(roughness, 0.0, 1.0);
#if LFX_PARTICLE_ANISOTROPY_TARGET >= 0
    #if LFX_PARTICLE_ANISOTROPIC_MATERIAL
    vec3 tangent = normalize(cross(cross(worldNormal, worldTangent), worldNormal)).xzy;
    lfxParticleTangentAnisotropy = vec4(tangent, clamp(anisotropy, -1.0, 1.0)) * 0.5 + 0.5;
    #if LFX_PARTICLE_ANISOTROPY_FLOAT && !LFX_PARTICLE_TOON_ADAPTIVE
    lfxParticleTangentAnisotropy = round(clamp(lfxParticleTangentAnisotropy, 0.0, 1.0) * 255.0) / 255.0;
    #endif
    #else
    lfxParticleTangentAnisotropy = vec4(0.5);
    #endif
#endif
#if LFX_DBUFFER_LOOKUP_ACTIVE && LFX_RECEIVES_DECALS
    vec2 uv = lfx_material_dbuffer_default_uv();
    if ((decalResponse & 1) != 0) {
        vec4 decal = lfx_material_dbuffer_0(uv);
        base = base * decal.a + decal.rgb;
    }
    if ((decalResponse & 2) != 0) {
        vec4 decal = lfx_material_dbuffer_1(uv);
        worldNormal = normalize(worldNormal * decal.a + decal.rgb.xzy);
    }
    if ((decalResponse & 4) != 0) {
        vec4 decal = lfx_material_dbuffer_2(uv);
        roughness = roughness * decal.a + decal.r;
        if (shadingModel != LFX_SHADING_MODEL_SUBSTRATE_EYE) specular = specular * decal.a + decal.g;
        if (shadingModel != LFX_SHADING_MODEL_SUBSTRATE_EYE && shadingModel != LFX_SHADING_MODEL_SUBSTRATE_HAIR)
            metallic = metallic * decal.a + decal.b;
    }
#endif
#if LFX_PARTICLE_SUBSTRATE_HAIR || LFX_PARTICLE_SUBSTRATE_EYE || LFX_PARTICLE_SUBSTRATE_TOON
#if LFX_PARTICLE_SUBSTRATE_TOON
    bool visibleClosure = customData.z > 0.0;
    #if LFX_PARTICLE_TOON_ADAPTIVE
    vec2 sourcePixel = gl_FragCoord.xy;
    if ((LfxSubstratePackingView.w & 1) != 0) sourcePixel.y = float(LfxSubstratePackingView.z) - sourcePixel.y;
    float dither = lfx_interleaved_gradient_noise(sourcePixel, float(LfxSubstratePackingView.x));
    base = lfx_toon_color665(base, dither);
    specular = lfx_toon_scalar5(specular, dither);
    metallic = lfx_toon_scalar5(metallic, dither);
    roughness = lfx_toon_scalar5(roughness, dither);
    #else
    base = srgbToLinear(round(linearToSrgb(base) * 255.0) / 255.0);
    specular = round(specular * 255.0) / 255.0;
    metallic = round(metallic * 255.0) / 255.0;
    roughness = round(roughness * 255.0) / 255.0;
    #endif
#else
    bool visibleClosure = customData.a > 0.0;
    base = lfx_substrate_color8(base);
    roughness = float(lfx_substrate_pack8(roughness)) / 255.0;
#endif
    ambientOcclusion = float(lfx_substrate_pack8(ambientOcclusion)) / 255.0;
    worldNormal = lfx_substrate_top_normal(worldNormal.xzy).xzy;
#endif
    vec2 sourceMetalSpecular = vec2(metallic, specular);
#if LFX_PARTICLE_SUBSTRATE_HAIR
    sourceMetalSpecular = vec2(lfx_substrate_pack8(metallic), lfx_substrate_pack8(specular)) / 255.0;
    customData = vec4(0.0, 0.0, float(lfx_substrate_pack8(customData.z)) / 255.0, 0.0);
#endif
#if LFX_PARTICLE_SUBSTRATE_EYE
    sourceMetalSpecular = vec2(lfx_substrate_pack8(customData.z), lfx_substrate_pack8(irisDistance)) / 255.0;
    customData = vec4(float(LFX_PARTICLE_PROFILE_INDEX) / 255.0, lfx_pack_eye_normal(bottomNormal.xzy),
                      lfx_pack_eye_normal(eyePlane.xzy), 1.0);
#endif
    if (shadingModel == LFX_SHADING_MODEL_CLEAR_COAT && !unityClearCoat) {
        roughness = clamp(roughness, 0.0, 254.0 / 255.0);
#if LFX_CLEAR_COAT_BOTTOM_NORMAL
        customData.wz = vec2(128.0 / 255.0);
        if (dot(bottomNormal, bottomNormal) > 0.0)
            customData.wz += 0.25 * (lfx_clear_coat_octahedron(bottomNormal.xzy) - lfx_clear_coat_octahedron(worldNormal.xzy));
#endif
    }
    if (shadingModel == LFX_SHADING_MODEL_HAIR)
        customData.xy = lfx_clear_coat_octahedron(normalize(worldNormal.xzy)) * 0.5 + 0.5;
    if (shadingModel == LFX_SHADING_MODEL_SUBSURFACE_PROFILE && profileIndex >= 0) {
        if (customData.a > 0.10) {
            customData.r = float(profileIndex) / 255.0;
        } else {
            shadingModel = 1;
            customData = vec4(0.0);
        }
    }
#if defined(LFX_PARTICLE_PROFILE_INDEX) && LFX_PARTICLE_PROFILE_INDEX >= 0
    if (shadingModel == LFX_SHADING_MODEL_EYE) {
        bool hasEyePlane = dot(eyePlane, eyePlane) > 0.0;
        irisDistance = clamp(irisDistance, 0.0, 1.0);
        metallic = 0.0;
        customData.r = float(LFX_PARTICLE_PROFILE_INDEX) / 255.0;
#if LFX_LEGACY_IRIS_NORMAL
        vec3 plane = hasEyePlane ? normalize(eyePlane) : worldNormal;
        vec3 iris = dot(bottomNormal, bottomNormal) > 0.0 ? normalize(bottomNormal) : plane;
        vec2 worldOct = lfx_clear_coat_octahedron(worldNormal.xzy);
        customData.yz = (lfx_clear_coat_octahedron(iris.xzy) - worldOct) * 0.5 + 128.0 / 255.0;
        sourceMetalSpecular = vec2(128.0 / 255.0);
        if (hasEyePlane) {
            vec3 caustic = normalize(mix(plane, -worldNormal, (1.0 - customData.a) * irisDistance));
            sourceMetalSpecular += (lfx_clear_coat_octahedron(caustic.xzy) - worldOct) * 0.5;
        }
        specular = 0.25;
#else
        sourceMetalSpecular.x = irisDistance;
        if (hasEyePlane) customData.yz = lfx_clear_coat_octahedron(normalize(eyePlane).xzy) * 0.5 + 0.5;
#endif
    }
#endif
    vec3 diffuse = base * (1.0 - metallic);
    vec3 f0 = mix(vec3(0.08 * specular), base, metallic);
    if (unitySpecularWorkflow) {
        diffuse = base * (1.0 - lfx_unity_specular_reflectivity(specularColor));
        f0 = specularColor;
    }
    if (unityUrpLit && !unitySpecularWorkflow) diffuse *= 0.96;
    if (unityClearCoat) {
        float reflectivity = unitySpecularWorkflow ? lfx_unity_specular_reflectivity(specularColor) : 0.04 + 0.96 * metallic;
        customData.z = clamp(1.0 - roughness + reflectivity, 0.0, 1.0);
        if (!unitySpecularWorkflow) diffuse *= 0.96;
        vec4 material = lfx_unity_coat_base(vec4(f0, roughness), customData.x);
        f0 = material.rgb;
        roughness = material.w;
    }
#if LFX_PARTICLE_SUBSTRATE_HAIR
    diffuse = base;
    f0 = vec3(0.08 * sourceMetalSpecular.y);
#elif LFX_PARTICLE_SUBSTRATE_EYE
    diffuse = base;
    f0 = vec3(0.028);
#endif
    vec3 normal = normalize(transpose(inverse(mat3(ModelViewMat))) * worldNormal);
    float depth = gl_FragCoord.z;
#if LFX_MATERIAL_WRITES_DEPTH
    cameraRelativePosition = lfxDepthOffsetWorldPosition;
    depth = lfxMaterialDeviceZ;
#elif LFX_NANITE_MATERIAL
    depth = lfxNaniteGeometry.deviceDepth;
#endif
    float gbufferAo = clamp(ambientOcclusion, 0.0, 1.0);
    int indirectChannel = 0;
#if LFX_OPAQUE_INDIRECT && LFX_PARTICLE_SUBSTRATE_TOON
    vec3 irradiance;
    float diffuseScale = lfx_toon_settings(lfx_toon_profile_layer(customData), 3).x;
    lfxOpaqueIndirectDiffuse = lfx_scene_indirect_diffuse(cameraRelativePosition, worldNormal.xzy, base,
            diffuse * diffuseScale, gbufferAo, false, vec3(0.0), irradiance) * customData.z;
#endif
#if !LFX_PARTICLE_SUBSTRATE_PACKING
    vec3 indirectNormal = worldNormal;
#if LFX_CLEAR_COAT_BOTTOM_NORMAL
    if (shadingModel == LFX_SHADING_MODEL_CLEAR_COAT && !unityClearCoat && dot(bottomNormal, bottomNormal) > 0.0)
        indirectNormal = normalize(bottomNormal);
#endif
    vec3 view = normalize(-cameraRelativePosition);
    LfxShadingOcclusion occlusion = lfx_material_shading_occlusion(indirectNormal, authoredBentNormal,
            view, roughness, ambientOcclusion, hasBentNormal);
    float specularLuminance = dot(f0, vec3(0.2126390059, 0.7151686788, 0.0721923154));
    if (!unityClearCoat) gbufferAo = lfx_indirect_ao_multibounce(vec3(specularLuminance), occlusion.specular).g;
#if LFX_OPAQUE_INDIRECT
    if (!lfx_surface_uses_subsurface_profile(shadingModel)) {
        vec3 scatter = customData.rgb * customData.rgb;
        vec3 indirectColor = diffuse;
        if (unityClearCoat)
            indirectColor *= lfx_unity_coat_transmission(customData.x, lfx_unity_coat_fresnel_power(dot(indirectNormal, view)));
        if (shadingModel == LFX_SHADING_MODEL_SUBSURFACE || shadingModel == LFX_SHADING_MODEL_PREINTEGRATED_SKIN)
            indirectColor += scatter;
        if (shadingModel == LFX_SHADING_MODEL_CLOTH) indirectColor += scatter * customData.a;
        vec3 lookupNormal = occlusion.bentNormal;
        if (shadingModel == LFX_SHADING_MODEL_HAIR)
            indirectColor = lfx_hair_environment(base, metallic, specular, roughness, worldNormal, view, lookupNormal);
        vec3 irradiance;
        lfxOpaqueIndirectDiffuse = lfx_scene_indirect_diffuse(cameraRelativePosition, lookupNormal.xzy, base,
                indirectColor, occlusion.diffuse, shadingModel == LFX_SHADING_MODEL_TWOSIDED_FOLIAGE, scatter, irradiance);
        indirectChannel = int(round(lfx_precomputed_irradiance_channel(irradiance, gbufferAo)));
    }
#endif
#endif
    vec3 position = (ModelViewMat * vec4(cameraRelativePosition, 1.0)).xyz;
    lfxParticleDiffuse = vec4(linearToSrgb(diffuse), 1.0);
    lfxParticleNormalAo = vec4(normal, gbufferAo);
    int flags = LFX_DEFAULT_LIT_PARTICLE_FLAG | LFX_PARTICLE_CONTACT_SHADOW | (LFX_PARTICLE_LIGHT_CHANNELS << LFX_PARTICLE_LIGHT_CHANNEL_SHIFT);
    flags |= shadingModel << LFX_PARTICLE_SHADING_MODEL_SHIFT;
    if (unityClearCoat) flags |= LFX_PARTICLE_UNITY_COAT_FLAG;
    if (unityUrpLit) flags |= LFX_PARTICLE_URP_LIT_FLAG;
#if LFX_PARTICLE_FIRST_PERSON
    flags |= LFX_PARTICLE_FIRST_PERSON_FLAG;
#endif
#if LFX_PARTICLE_SUBSTRATE_EYE
    flags &= ~LFX_PARTICLE_CONTACT_SHADOW;
#endif
#if LFX_PARTICLE_SUBSTRATE_HAIR || LFX_PARTICLE_SUBSTRATE_EYE || LFX_PARTICLE_SUBSTRATE_TOON
    if (!visibleClosure) flags = LFX_PARTICLE_CONTACT_SHADOW | LFX_UNLIT_PARTICLE_FLAG;
#endif
#if LFX_PARTICLE_ANISOTROPIC_MATERIAL
    flags |= LFX_ANISOTROPIC_PARTICLE_FLAG;
#endif
    lfxParticlePositionDepth = vec4(position.xy, float(flags), depth);
    lfxParticleF0Roughness = vec4(f0, roughness);
#if LFX_PARTICLE_CUSTOM_DATA_TARGET >= 0
    #if LFX_PARTICLE_CUSTOM_DATA_FLOAT && !LFX_PARTICLE_SUBSTRATE_TOON
    if (!unityClearCoat) customData = round(clamp(customData, 0.0, 1.0) * 255.0) / 255.0;
    #endif
    lfxParticleCustomData = customData;
#endif
#if LFX_PARTICLE_GBUFFER
    ivec2 packedSource = ivec2(round(clamp(sourceMetalSpecular, 0.0, 1.0) * 255.0));
    int packedMetalSpecular = packedSource.x | (packedSource.y << 8);
    int highBits = shadingModel >= LFX_SHADING_MODEL_SUBSTRATE ? LFX_PARTICLE_FIRST_PERSON : indirectChannel;
    lfxParticleSourceMaterial = vec4(base, float(packedMetalSpecular | (highBits << 16)));
#endif
}

void lfx_write_lit_surface(vec3 base, float metallic, float specular, float roughness,
                           float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                           float anisotropy, vec3 worldTangent, int shadingModel, vec4 customData, vec3 bottomNormal,
                           vec3 eyePlane, float irisDistance, int profileIndex, vec3 authoredBentNormal, bool hasBentNormal,
                           vec3 specularColor, bool unitySpecularWorkflow) {
    lfx_write_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
            decalResponse, anisotropy, worldTangent, shadingModel, customData, bottomNormal, eyePlane, irisDistance,
            profileIndex, authoredBentNormal, hasBentNormal, specularColor, unitySpecularWorkflow, false, false);
}

void lfx_write_lit_surface(vec3 base, float metallic, float specular, float roughness,
                           float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                           float anisotropy, vec3 worldTangent, int shadingModel, vec4 customData, vec3 bottomNormal,
                           vec3 eyePlane, float irisDistance, int profileIndex, vec3 authoredBentNormal, bool hasBentNormal) {
    lfx_write_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
            decalResponse, anisotropy, worldTangent, shadingModel, customData, bottomNormal, eyePlane, irisDistance,
            profileIndex, authoredBentNormal, hasBentNormal, vec3(0.0), false);
}

void lfx_write_lit_surface(vec3 base, float metallic, float specular, float roughness,
                           float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                           float anisotropy, vec3 worldTangent, int shadingModel, vec4 customData, vec3 bottomNormal,
                           vec3 eyePlane, float irisDistance, int profileIndex) {
    lfx_write_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
            decalResponse, anisotropy, worldTangent, shadingModel, customData, bottomNormal, eyePlane, irisDistance,
            profileIndex, worldNormal, false);
}

void lfx_write_lit_surface(vec3 base, float metallic, float specular, float roughness,
                           float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                           float anisotropy, vec3 worldTangent, int shadingModel, vec4 customData, vec3 bottomNormal,
                           vec3 eyePlane, float irisDistance) {
#if defined(LFX_PARTICLE_PROFILE_INDEX)
    const int profileIndex = LFX_PARTICLE_PROFILE_INDEX;
#else
    const int profileIndex = -1;
#endif
    lfx_write_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
            decalResponse, anisotropy, worldTangent, shadingModel, customData, bottomNormal, eyePlane, irisDistance, profileIndex);
}

void lfx_write_lit_surface(vec3 base, float metallic, float specular, float roughness,
                           float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                           float anisotropy, vec3 worldTangent, int shadingModel, vec4 customData, vec3 bottomNormal) {
    lfx_write_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
            decalResponse, anisotropy, worldTangent, shadingModel, customData, bottomNormal, vec3(0.0), 0.0);
}

void lfx_write_default_lit_surface(vec3 base, float metallic, float specular, float roughness,
                                   float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse,
                                   float anisotropy, vec3 worldTangent) {
    lfx_write_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
                          decalResponse, anisotropy, worldTangent, 1, vec4(0.0), vec3(0.0));
}

void lfx_write_default_lit_surface(vec3 base, float metallic, float specular, float roughness,
                                   float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition, int decalResponse) {
    lfx_write_default_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition,
                                  decalResponse, 0.0, vec3(0.0));
}

void lfx_write_default_lit_surface(vec3 base, float metallic, float specular, float roughness,
                                   float ambientOcclusion, vec3 worldNormal, vec3 cameraRelativePosition) {
    lfx_write_default_lit_surface(base, metallic, specular, roughness, ambientOcclusion, worldNormal, cameraRelativePosition, 0);
}

void lfx_write_unity_urp_surface(vec3 base, float metallic, vec3 specularColor, bool specularWorkflow,
                                 float roughness, float ambientOcclusion, vec3 normal, vec3 position) {
    float legacySpecular = specularWorkflow ? lfx_unity_specular_reflectivity(specularColor) / 0.08 : 0.5;
    lfx_write_lit_surface(base, metallic, legacySpecular, roughness, ambientOcclusion, normal, position,
            0, 0.0, vec3(0.0), 1, vec4(0.0), vec3(0.0), vec3(0.0), 0.0, -1, normal, false,
            specularColor, specularWorkflow, false, true);
}

void lfx_write_unity_specular_surface(vec3 base, vec3 specularColor, float roughness, float ambientOcclusion,
                                      vec3 worldNormal, vec3 cameraRelativePosition) {
    lfx_write_unity_urp_surface(base, 0.0, specularColor, true, roughness, ambientOcclusion, worldNormal, cameraRelativePosition);
}

void lfx_write_unity_coated_surface(vec3 base, float metallic, vec3 specularColor, bool specularWorkflow,
                                    float roughness, float ambientOcclusion, vec3 worldNormal, vec3 position,
                                    float coatMask, float coatRoughness) {
    float legacySpecular = specularWorkflow ? lfx_unity_specular_reflectivity(specularColor) / 0.08 : 0.5;
    lfx_write_lit_surface(base, metallic, legacySpecular, roughness, ambientOcclusion, worldNormal, position,
            0, 0.0, vec3(0.0), LFX_SHADING_MODEL_CLEAR_COAT, vec4(coatMask, coatRoughness, 0.0, 0.0),
            vec3(0.0), vec3(0.0), 0.0, -1, worldNormal, false, specularColor, specularWorkflow, true, false);
}
#endif
