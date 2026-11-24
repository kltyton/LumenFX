#moj_import <lumenfx:substrate/adaptive_closure_encoding.glsl>
#if LFX_SUBSTRATE_ADAPTIVE_REPLAY && LFX_SUBSTRATE_ADAPTIVE_PROFILE
#moj_import <lumenfx:lighting/subsurface_profile.glsl>
#endif

#if LFX_SUBSTRATE_ADAPTIVE_REPLAY
void lfx_write_adaptive_header(uint count, vec3 normal, vec2 roughnessWeight, bool diffusionLayout,
        LfxSlabDiffusionPixel diffusion) {
    lfxAdaptiveWords[0] = count;
    if (count == 0u) return;
    bool preciseNormal = (LfxSubstratePackingView.w & 2) != 0;
    normal = normalize(normal);
    uint roughness = lfx_substrate_pack8(roughnessWeight.x / max(roughnessWeight.y, 1e-10));
    if (preciseNormal) lfxAdaptiveWords[1] = lfx_adaptive_pack_normal(normal);
    else {
        vec2 oct = lfx_clear_coat_octahedron(normal);
        uvec2 packedValue = uvec2(clamp(oct * 1023.0 + 1024.0, 0.0, 2047.0));
        lfxAdaptiveWords[1] = packedValue.x | (packedValue.y << 11);
    }
    uint flags = 1u | (roughness << 2) | (preciseNormal ? LFX_ADAPTIVE_TOP_PRECISE_NORMAL : 0u);
    if (diffusionLayout) flags |= LFX_ADAPTIVE_DIFFUSION_LAYOUT;
    if (diffusion.type != 0) {
        bool preciseColor = LfxSubstratePackingView.y >= 1;
        flags |= LFX_ADAPTIVE_DIFFUSION_PRESENT | (preciseColor ? LFX_ADAPTIVE_DIFFUSION_PRECISE_COLOR : 0u);
        if (diffusion.type == 2) flags |= LFX_ADAPTIVE_DIFFUSION_IS_PROFILE;
        lfxAdaptiveWords[3] = lfx_adaptive_pack_color8(diffusion.baseColor, preciseColor);
        lfxAdaptiveWords[4] = diffusion.type == 2 ? diffusion.profileData : lfx_adaptive_pack_diffusion_mfp(diffusion.meanFreePath);
    }
    lfxAdaptiveWords[2] = flags;
}

void lfx_write_adaptive_slab(int offset, uint layoutBits, int decalResponse,
        vec3 worldNormal, vec3 worldTangent, float anisotropy, vec3 diffuse, vec3 f0, vec3 f90, float roughness,
        float secondRoughness, float secondWeight, float simpleCoat, vec2 coatNormalOct,
        vec3 fuzzColor, float fuzzAmount, float fuzzRoughness, vec3 luminance,
        float coverageAbove, vec3 transmittanceAbove, vec3 mfp, float phase, int wrap, float specularProfile,
        float topWeight, inout vec3 topNormal, inout vec2 topRoughnessWeight, bool canDiffuse,
        vec3 diffusionMfp, float diffusionPhase, int diffusionProfile, float radiusScale, float thickness, bool thinSurface,
        float coverage, inout LfxSlabDiffusionPixel diffusionPixel) {
    bool hasProfile = canDiffuse && diffusionProfile >= 0 && radiusScale > 0.0;
    bool hasMfp = canDiffuse && wrap == 0 && any(greaterThan(diffusionMfp, vec3(0.0)));
    vec3 decalMfp = hasMfp ? diffusionMfp : mfp;
    lfx_apply_slab_decal(decalResponse, worldNormal, diffuse, f0, roughness, anisotropy, secondWeight, fuzzAmount, decalMfp);
    if (hasMfp) diffusionMfp = decalMfp;
    else mfp = decalMfp;
    if (hasProfile || hasMfp) {
        wrap = 0;
        phase = diffusionPhase;
    }
#if LFX_SUBSTRATE_ADAPTIVE_PROFILE
    if (hasProfile) {
        vec3 dualSpecular = lfx_subsurface_profile_load(diffusionProfile, 5).xyz;
        float opacity = clamp((radiusScale - 0.10) * 10.0, 0.0, 1.0);
        vec2 lobes = clamp(roughness * mix(vec2(1.0), dualSpecular.xy * 2.0, opacity), 0.0, 1.0);
        roughness = max(lobes.x, 0.02);
        secondRoughness = lobes.y;
        secondWeight = dualSpecular.z;
    }
#endif
    vec2 sourcePixel = gl_FragCoord.xy;
    if ((LfxSubstratePackingView.w & 1) != 0) sourcePixel.y = float(LfxSubstratePackingView.z) - sourcePixel.y;
    float dither = lfx_interleaved_gradient_noise(sourcePixel, float(LfxSubstratePackingView.x));
    bool precise = LfxSubstratePackingView.y >= 1;
#if !LFX_PARTICLE_ANISOTROPIC_MATERIAL
    anisotropy = 0.0;
#endif
    uint settings = layoutBits | (uint(wrap) << 12) | (precise ? LFX_ADAPTIVE_PRECISE_COLOR : 0u);
    if (anisotropy != 0.0) settings |= 32768u | (lfx_substrate_pack8(anisotropy * 0.5 + 0.5) << 16);
    if ((layoutBits & (LFX_ADAPTIVE_WRAP | LFX_ADAPTIVE_VOLUME)) != 0u || hasMfp)
        settings |= lfx_substrate_pack8(phase * 0.5 + 0.5) << 24;

    vec3 sourceNormal = normalize(worldNormal.xzy);
    topNormal += topWeight * sourceNormal;
    float topRoughness = simpleCoat > 0.0 ? mix(roughness, secondRoughness, secondWeight) : roughness;
    topRoughnessWeight += vec2(topRoughness, 1.0) * topWeight;
    uint basis = lfx_adaptive_pack_normal(sourceNormal);
    if (anisotropy != 0.0) {
        vec3 tangent = normalize(cross(cross(worldNormal, worldTangent), worldNormal)).xzy;
        basis = lfx_substrate_pack_normal_tangent(sourceNormal, tangent);
    }
    lfxAdaptiveWords[offset++] = settings;
    lfxAdaptiveWords[offset++] = basis;
    lfxAdaptiveWords[offset++] = lfx_substrate_pack_color20(diffuse, dither, precise);
    bool greyWeight = all(equal(luminance, luminance.xxx));
    uint diffusionBits = (hasProfile || hasMfp) ? LFX_ADAPTIVE_HAS_DIFFUSION : 0u;
    if (hasProfile) diffusionBits |= LFX_ADAPTIVE_DIFFUSION_PROFILE;
    if (thinSurface && hasMfp) diffusionBits |= LFX_ADAPTIVE_DIFFUSION_THIN;
    lfxAdaptiveWords[offset++] = lfx_substrate_pack_color20(f0, dither, precise) | (lfx_substrate_pack8(roughness) << 20)
            | (greyWeight ? LFX_ADAPTIVE_GREY_WEIGHT : 0u) | diffusionBits;
    lfxAdaptiveWords[offset++] = greyWeight ? lfx_wrap_half(min(luminance.x, 64512.0)) >> 5 : lfx_adaptive_pack_unsigned(luminance);
    if ((layoutBits & LFX_ADAPTIVE_EDGE) != 0u)
        lfxAdaptiveWords[offset++] = lfx_substrate_pack_edge_color(f90);
    if ((layoutBits & LFX_ADAPTIVE_HAZE) != 0u) {
        uint haze = lfx_substrate_pack8(secondRoughness) | (uint(clamp(secondWeight, 0.0, 1.0) * 127.0) << 8)
                | (simpleCoat > 0.0 ? 32768u : 0u);
#if LFX_CLEAR_COAT_BOTTOM_NORMAL
        uvec2 coat = uvec2(round(clamp(coatNormalOct, 0.0, 1.0) * 255.0));
        haze |= (coat.x | (coat.y << 8)) << 16;
#endif
        lfxAdaptiveWords[offset++] = haze;
    }
    if ((layoutBits & LFX_ADAPTIVE_FUZZ) != 0u)
        lfxAdaptiveWords[offset++] = lfx_substrate_pack_color20(fuzzColor, dither, precise)
                | (lfx_substrate_pack6(fuzzAmount, dither) << 20) | (lfx_substrate_pack6(fuzzRoughness, dither) << 26);
    if ((layoutBits & LFX_ADAPTIVE_WRAP) != 0u) lfxAdaptiveWords[offset++] = lfx_adaptive_pack_unsigned(mfp);
    if ((layoutBits & LFX_ADAPTIVE_VOLUME) != 0u) lfxAdaptiveWords[offset++] = lfx_adaptive_pack_unsigned(mfp * 100.0);
    if ((layoutBits & LFX_ADAPTIVE_SPECULAR_PROFILE) != 0u) lfxAdaptiveWords[offset++] = uint(int(specularProfile) + 255);
    if ((layoutBits & LFX_ADAPTIVE_ABOVE) != 0u)
        lfxAdaptiveWords[offset++] = lfx_adaptive_pack_color8(transmittanceAbove, precise) | (lfx_substrate_pack8(coverageAbove) << 24);
    if (hasProfile || hasMfp) {
        if (!hasProfile && thinSurface) diffusionMfp *= 100.0 / max(thickness, 1e-10);
        uint data = hasProfile ? lfx_adaptive_pack_diffusion_profile(diffusionProfile, radiusScale, thickness)
                : lfx_adaptive_pack_unsigned(diffusionMfp);
        lfxAdaptiveWords[offset] = data;
        diffusionPixel.type = hasProfile ? 2 : 1;
        if (hasProfile) diffusionPixel.profileData = data;
        else diffusionPixel.meanFreePath += diffusionMfp * coverage;
        diffusionPixel.baseColor += diffuse * coverage;
    }
}
#endif
