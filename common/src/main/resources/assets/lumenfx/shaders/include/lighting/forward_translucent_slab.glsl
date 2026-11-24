#moj_import <lumenfx:lighting/translucent_slab_env.glsl>

float lfx_slab_aniso_ggx(float roughness, float anisotropy, float noH, float noV, float noL,
                         vec2 tangentH, vec2 tangentV, vec2 tangentL) {
    float alpha = roughness * roughness;
    vec2 axis = max(alpha * vec2(1.0 + anisotropy, 1.0 - anisotropy), vec2(0.001));
    float area = axis.x * axis.y;
    vec3 halfSlope = vec3(axis.yx * tangentH, area * noH);
    float slopeRatio = area / max(dot(halfSlope, halfSlope), 1e-8);
    float ndf = area * slopeRatio * slopeRatio / LFX_BRDF_PI;
    float visV = noL * length(vec3(axis * tangentV, noV));
    float visL = noV * length(vec3(axis * tangentL, noL));
    return ndf * (0.5 / max(visV + visL, 1e-6));
}

vec3 lfx_slab_direct_lobe(float roughness, vec3 f0, vec3 f90, vec3 normal, vec3 view, vec3 direction,
                          float noL, LfxPointAreaLight area, bool anisotropic, float anisotropy,
                          vec3 tangent, vec3 bitangent) {
    if (noL <= 0.0) return vec3(0.0);
    float rawNoV = dot(normal, view);
    float noV = clamp(max(abs(rawNoV), 1e-5), 0.0, 1.0);
    float voL = dot(view, direction);
    float rawNoL = dot(normal, direction);
    vec2 halfVector;
    float ggx;
    if (anisotropic) {
        halfVector = lfx_default_lit_half_vector(rawNoV, rawNoL, voL, 0.0, false);
        vec2 tangentV = vec2(dot(tangent, view), dot(bitangent, view));
        vec2 tangentL = vec2(dot(tangent, direction), dot(bitangent, direction));
        vec2 tangentH = (tangentV + tangentL) * inversesqrt(max(1e-8, 2.0 + 2.0 * voL));
        ggx = lfx_slab_aniso_ggx(roughness, anisotropy, halfVector.x, noV, noL, tangentH, tangentV, tangentL);
    } else {
        halfVector = lfx_default_lit_half_vector(rawNoV, rawNoL, voL, area.sphereSinAlpha, true);
        ggx = lfx_ggx_area(roughness, noV, noL, halfVector, area.sphereSinAlpha, area.sphereSinAlphaSoft,
                area.lineCosSubtended, true, false);
    }
    float peakF0 = max(f0.r, max(f0.g, f0.b));
    vec3 effF90 = f90 * clamp(50.0 * peakF0, 0.0, 1.0);
    float edge = 1.0 - halfVector.y;
    float edge2 = edge * edge;
    vec3 fresnel = mix(f0, effF90, edge2 * edge2 * edge);
    return fresnel * ggx;
}

vec3 lfx_slab_direct_eval(LfxPointAreaLight area, vec3 normal, vec3 view, vec3 diffuseColor,
                          vec3 f0, vec3 f90, float roughness, float secondRoughness, float secondWeight,
                          bool simpleCoat, bool anisotropic, float anisotropy, vec3 tangent, vec3 bitangent,
                          vec3 fuzzColor, float fuzzA, float fuzzR, float fuzzAtten,
                          vec3 radiance, vec3 diffuseRadiance, vec2 scales, float shadow,
                          LfxReflectionEnergy energy) {
    vec3 direction = area.specularDirection;
    float noL = area.normalDotLight;
    float noV = clamp(abs(dot(normal, view)) + 1e-5, 0.0, 1.0);
    vec3 diffuse = (diffuseColor / LFX_BRDF_PI) * (noL * energy.diffuseTransmission * fuzzAtten);
    vec3 spec = lfx_slab_direct_lobe(roughness, f0, f90, normal, view, direction, noL, area,
            anisotropic, anisotropy, tangent, bitangent) * energy.multiScatter;
    if (secondWeight > 0.0) {
        vec3 secF0 = simpleCoat ? vec3(0.04) : f0;
        vec3 secF90 = simpleCoat ? vec3(1.0) : f90;
        vec3 secSpec = lfx_slab_direct_lobe(clamp(secondRoughness, 0.02, 1.0), secF0, secF90, normal, view,
                direction, noL, area, false, 0.0, tangent, bitangent) * energy.multiScatter;
        spec = simpleCoat ? spec * (1.0 - 0.04 * secondWeight) + secSpec * secondWeight
                : mix(spec, secSpec, clamp(secondWeight, 0.0, 1.0));
    }
    spec *= noL * area.falloff * fuzzAtten;
    vec3 fuzzDirect = vec3(0.0);
    if (fuzzA > 0.0 && noL > 0.0) {
        vec3 h = normalize(view + direction);
        float noH = clamp(dot(normal, h), 0.0, 1.0);
        float sheen = lfx_slab_sheen_d(fuzzR, noH) * lfx_slab_sheen_v(noV, noL);
        fuzzDirect = fuzzColor * (fuzzA * sheen * noL * area.falloff);
    }
    return radiance * (diffuse * diffuseRadiance * scales.x + (spec + fuzzDirect) * scales.y) * shadow;
}

vec3 lfx_forward_translucent_slab(vec3 position, vec3 normal, vec3 view,
                                  vec3 diffuseColor, vec3 f0, vec3 f90, float roughness, float materialAo,
                                  float anisotropy, vec3 worldTangent, bool hasTangent,
                                  float secondRoughness, float secondWeight, bool simpleCoat,
                                  vec3 fuzzColor, float fuzzAmount, float fuzzRoughness,
                                  vec3 transmittanceAbove, float coverageAbove) {
    int cellIndex = lfx_forward_grid_cell(position, ForwardGridShape, ForwardGridZ, ForwardWorldToView);
    uvec2 cell = texelFetch(LfxForwardLightHeaders, cellIndex).rg;
    vec3 sourcePosition = position.xzy * 100.0;
    roughness = clamp(roughness, 0.02, 1.0);
    anisotropy = clamp(anisotropy, -1.0, 1.0);
    vec3 tangent = vec3(1.0, 0.0, 0.0);
    vec3 bitangent = vec3(0.0, 1.0, 0.0);
    bool anisotropic = false;
    if (hasTangent && abs(anisotropy) > 1e-4) {
        vec3 proj = worldTangent - normal * dot(worldTangent, normal);
        float projLen = dot(proj, proj);
        if (projLen > 1e-8) {
            tangent = proj * inversesqrt(projLen);
            bitangent = cross(normal, tangent);
            anisotropic = true;
        }
    }
    float noV = clamp(abs(dot(normal, view)) + 1e-5, 0.0, 1.0);
    float fuzzA = clamp(fuzzAmount, 0.0, 1.0);
    float fuzzR = clamp(fuzzRoughness, 0.02, 1.0);
    float fuzzAlbedo = fuzzA > 0.0 ? lfx_slab_sheen_albedo(noV, fuzzR) : 0.0;
    float fuzzAtten = 1.0 - fuzzA * fuzzAlbedo;
    vec3 lightThru = mix(vec3(1.0), clamp(transmittanceAbove, 0.0, 1.0), clamp(coverageAbove, 0.0, 1.0));

    vec3 result = lfx_translucent_slab_environment(position, normal, view, diffuseColor, f0, f90, roughness, materialAo,
            anisotropy, worldTangent, hasTangent, fuzzColor, fuzzA, fuzzR, transmittanceAbove, coverageAbove);
    LfxReflectionEnergy energy = lfx_legacy_reflection_energy(roughness, noV, f0);

    if (ForwardDirectionalFlags.y != 0 && (ForwardDirectionalFlags.x & ForwardPrimitiveChannels.x) != 0) {
        vec3 direction = ForwardDirectionalDirection.xyz;
        LfxPointAreaLight area = lfx_forward_directional_area_light(roughness, normal, direction,
                ForwardDirectionalDirection.w, ForwardDirectionalColor.w);
        float depth = -(ForwardWorldToView * vec4(position, 1.0)).z;
        float staticShadow = lfx_forward_directional_static(position);
        float dynamicShadow = lfx_forward_directional_shadow(position, depth);
        float fade = clamp(depth * ForwardShadowFade.x + ForwardShadowFade.y, 0.0, 1.0);
        float shadow = mix(dynamicShadow, staticShadow, fade * fade);
        vec3 radiance = ForwardDirectionalColor.rgb * lfx_forward_atmosphere_transmittance(sourcePosition);
        if (ForwardMaterialSettings.y >= 0.0)
            radiance *= lfx_forward_light_function(int(ForwardMaterialSettings.y), sourcePosition);
        result += lightThru * lfx_slab_direct_eval(area, normal, view, diffuseColor, f0, f90, roughness,
                secondRoughness, secondWeight, simpleCoat, anisotropic, anisotropy, tangent, bitangent,
                fuzzColor, fuzzA, fuzzR, fuzzAtten, radiance, vec3(area.falloff), ForwardDirectionalScales, shadow, energy);
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
        LfxPointCapsule capsule = lfx_point_capsule(toLight, tangentLength.xyz, tangentLength.w, directionRadius.w, shape.x);
        LfxPointAreaLight area = lfx_create_point_area_light(roughness, normal, view, capsule, inverseSquare);
        result += lightThru * lfx_slab_direct_eval(area, normal, view, diffuseColor, f0, f90, roughness,
                secondRoughness, secondWeight, simpleCoat, anisotropic, anisotropy, tangent, bitangent,
                fuzzColor, fuzzA, fuzzR, fuzzAtten, radiance, vec3(area.falloff), scalesAndFlags.xy, 1.0, energy);
    }
    return result;
}
