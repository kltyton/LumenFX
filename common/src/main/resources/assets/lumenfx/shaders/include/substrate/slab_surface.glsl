#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:opaque_particle_unlit.glsl>
#moj_import <lumenfx:substrate/slab_coat_normal.glsl>

#moj_import <lumenfx:math/interleaved_gradient_noise.glsl>
#moj_import <lumenfx:substrate/surface_packing.glsl>
#moj_import <lumenfx:substrate/slab_basis.glsl>
#moj_import <lumenfx:substrate/wrap_encoding.glsl>

float lfx_slab_recover_metallic(float f0) {
    return clamp((f0 - 0.08) / (0.4 - 0.08), 0.0, 1.0);
}

void lfx_apply_slab_decal(int decalResponse, inout vec3 worldNormal, inout vec3 diffuse, inout vec3 f0,
        inout float roughness, inout float anisotropy, inout float secondWeight, inout float fuzzAmount, inout vec3 mfp) {
    diffuse = clamp(diffuse, 0.0, 1.0);
    f0 = clamp(f0, 0.0, 1.0);
    roughness = clamp(roughness, 0.0, 1.0);
#if LFX_DBUFFER_LOOKUP_ACTIVE && LFX_RECEIVES_DECALS
    vec2 uv = lfx_material_dbuffer_default_uv();
    if ((decalResponse & 2) != 0) {
        vec4 decalNormal = lfx_material_dbuffer_1(uv);
        worldNormal = normalize(worldNormal * decalNormal.a + decalNormal.rgb.xzy);
    }
    vec4 decalColor = (decalResponse & 1) != 0 ? lfx_material_dbuffer_0(uv) : vec4(0.0, 0.0, 0.0, 1.0);
    vec4 decalMaterial = (decalResponse & 4) != 0 ? lfx_material_dbuffer_2(uv) : vec4(0.0, 0.0, 0.0, 1.0);
    float minimumF0 = min(f0.r, min(f0.g, f0.b));
    float oldMetallic = lfx_slab_recover_metallic(minimumF0);
    f0 = f0 * decalColor.a + mix(vec3(0.08 * decalMaterial.g), decalColor.rgb, decalMaterial.b);
    diffuse = diffuse * decalColor.a + decalColor.rgb * (1.0 - decalMaterial.b);
    fuzzAmount *= decalColor.a;
    mfp *= decalColor.a;
    if ((decalResponse & 4) != 0) {
        float delta = oldMetallic * decalMaterial.a + decalMaterial.b - oldMetallic;
        vec3 transfer = delta >= 0.0 ? delta * diffuse : delta * clamp(f0 - clamp(min(f0.r, min(f0.g, f0.b)), 0.0, 0.08), 0.0, 1.0);
        diffuse -= transfer;
        f0 += transfer;
        roughness = roughness * decalMaterial.a + decalMaterial.r;
        anisotropy *= decalMaterial.a;
        secondWeight *= decalMaterial.a;
    }
#endif
}

#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION
void lfx_write_slab_surface(vec3 diffuse, vec3 f0, vec3 f90, float roughness, float ao, vec3 worldNormal,
                            vec3 cameraRelativePosition, float anisotropy, vec3 worldTangent, float coverage,
                            int decalResponse, float secondRoughness, float secondWeight, float simpleCoat, vec2 coatNormalOct,
                            vec3 fuzzColor, float fuzzAmount, float fuzzRoughness, vec3 wrapMfp, float wrapPhase,
                            int wrapType, bool thinSurface, float specularProfile) {
    f90 = clamp(f90, 0.0, 1.0);
    lfx_apply_slab_decal(decalResponse, worldNormal, diffuse, f0, roughness, anisotropy, secondWeight, fuzzAmount, wrapMfp);
#if LFX_PARTICLE_ANISOTROPY_TARGET >= 0
    #if LFX_PARTICLE_ANISOTROPIC_MATERIAL
    if (anisotropy != 0.0) {
        vec3 tangent = normalize(cross(cross(worldNormal, worldTangent), worldNormal)).xzy;
        uint packedBasis = lfx_substrate_pack_normal_tangent(normalize(worldNormal.xzy), tangent);
        float signedAnisotropy = float(lfx_substrate_pack8(anisotropy * 0.5 + 0.5)) / 255.0;
        lfxParticleTangentAnisotropy = vec4(float(packedBasis & 65535u), float(packedBasis >> 16), signedAnisotropy, 1.0);
    } else lfxParticleTangentAnisotropy = vec4(0.5);
    #else
    lfxParticleTangentAnisotropy = vec4(0.5);
    #endif
#endif
    float depth = gl_FragCoord.z;
#if LFX_MATERIAL_WRITES_DEPTH
    cameraRelativePosition = lfxDepthOffsetWorldPosition;
    depth = lfxMaterialDeviceZ;
#elif LFX_NANITE_MATERIAL
    depth = lfxNaniteGeometry.deviceDepth;
#endif
    vec3 normal = normalize(transpose(inverse(mat3(ModelViewMat))) * lfx_substrate_top_normal(worldNormal.xzy).xzy);
    vec3 position = (ModelViewMat * vec4(cameraRelativePosition, 1.0)).xyz;
    int flags = LFX_DEFAULT_LIT_PARTICLE_FLAG | LFX_PARTICLE_CONTACT_SHADOW
        | (LFX_PARTICLE_LIGHT_CHANNELS << LFX_PARTICLE_LIGHT_CHANNEL_SHIFT)
        | (LFX_SHADING_MODEL_SUBSTRATE << LFX_PARTICLE_SHADING_MODEL_SHIFT);
#if LFX_PARTICLE_ANISOTROPIC_MATERIAL
    if (anisotropy != 0.0) flags |= LFX_ANISOTROPIC_PARTICLE_FLAG;
#endif
    vec2 sourcePixel = gl_FragCoord.xy;
    if ((LfxSubstratePackingView.w & 1) != 0) sourcePixel.y = float(LfxSubstratePackingView.z) - sourcePixel.y;
    float dither = lfx_interleaved_gradient_noise(sourcePixel, float(LfxSubstratePackingView.x));
    bool preciseColor = LfxSubstratePackingView.y >= 1;
    vec3 packedDiffuse = lfx_substrate_unpack_color20(lfx_substrate_pack_color20(diffuse, dither, preciseColor), preciseColor);
    vec3 packedF0 = lfx_substrate_unpack_color20(lfx_substrate_pack_color20(f0, dither, preciseColor), preciseColor);
    lfxParticleDiffuse = vec4(linearToSrgb(packedDiffuse), 1.0);
    lfxParticleNormalAo = vec4(normal, float(lfx_substrate_pack8(ao)) / 255.0);
    lfxParticlePositionDepth = vec4(position.xy, float(flags), depth);
    lfxParticleF0Roughness = vec4(packedF0, float(lfx_substrate_pack8(roughness)) / 255.0);
    uint packedHaze = lfx_substrate_pack8(secondRoughness)
        | (uint(clamp(secondWeight, 0.0, 1.0) * 127.0) << 8) | (simpleCoat > 0.0 ? 32768u : 0u);
    uint packedCoverage = uint(round(clamp(coverage, 0.0, 1.0) * 255.0));
    uint coatNormal = 0u;
#if LFX_CLEAR_COAT_BOTTOM_NORMAL
    uvec2 bottomOctBytes = uvec2(round(clamp(coatNormalOct, 0.0, 1.0) * 255.0));
    coatNormal = bottomOctBytes.x | (bottomOctBytes.y << 8);
#endif
    bool singleSlab = anisotropy == 0.0 && coverage == 1.0;
    if (singleSlab && simpleCoat > 0.0) wrapType = 0;
    bool compactWrap = wrapType != 0 && singleSlab && fuzzAmount <= 0.0 && !thinSurface;
    uint edgeColor = lfx_substrate_pack_edge_color(f90);
    vec3 opticalData = lfx_pack_slab_wrap(edgeColor, coatNormal, wrapMfp, wrapPhase, wrapType, compactWrap);
    if (opticalData.b < 0.0) {
        opticalData.r = float((lfx_wrap_magnitude(opticalData.r) & ~131071u) | 65536u);
        packedHaze = 0u;
    }
    int storedWrapType = lfx_slab_wrap_bits(opticalData) == 0u ? 0 : wrapType;
    lfxParticleCustomData = vec4(opticalData, float(packedHaze | (packedCoverage << 16)));
#if LFX_PARTICLE_GBUFFER
    uint fuzz = lfx_substrate_pack6(fuzzAmount, dither) | (lfx_substrate_pack6(fuzzRoughness, dither) << 6);
    uint packedFuzz = lfx_substrate_pack_color20(fuzzColor, dither, preciseColor);
    uint sourceFlags = (uint(LFX_PARTICLE_FIRST_PERSON) << 16) | (uint(fuzzAmount > 0.0) << 17)
        | (uint(storedWrapType) << 18) | (uint((packedHaze & 32768u) != 0u) << 20);
    if (specularProfile != 0.0) {
        sourceFlags |= LFX_SLAB_PROFILE_PAYLOAD | (preciseColor ? LFX_SLAB_PROFILE_PRECISE_FUZZ : 0u);
        lfxParticleSourceMaterial = vec4(float(packedFuzz), specularProfile, 0.0, float(fuzz | sourceFlags));
    } else {
        lfxParticleSourceMaterial = vec4(lfx_substrate_unpack_color20(packedFuzz, preciseColor), float(fuzz | sourceFlags));
    }
#endif
}

void lfx_write_slab_surface(vec3 diffuse, vec3 f0, vec3 f90, float roughness, float ao, vec3 worldNormal,
                            vec3 cameraRelativePosition, float anisotropy, vec3 worldTangent, float coverage,
                            int decalResponse, float secondRoughness, float secondWeight, float simpleCoat, vec2 coatNormalOct,
                            vec3 fuzzColor, float fuzzAmount, float fuzzRoughness, vec3 wrapMfp, float wrapPhase,
                            int wrapType, bool thinSurface) {
    lfx_write_slab_surface(diffuse, f0, f90, roughness, ao, worldNormal, cameraRelativePosition, anisotropy, worldTangent,
            coverage, decalResponse, secondRoughness, secondWeight, simpleCoat, coatNormalOct,
            fuzzColor, fuzzAmount, fuzzRoughness, wrapMfp, wrapPhase, wrapType, thinSurface, 0.0);
}

void lfx_write_slab_surface(vec3 diffuse, vec3 f0, vec3 f90, float roughness, float ao, vec3 worldNormal,
                            vec3 cameraRelativePosition, float anisotropy, vec3 worldTangent, float coverage,
                            int decalResponse, float secondRoughness, float secondWeight, float simpleCoat, vec2 coatNormalOct,
                            vec3 fuzzColor, float fuzzAmount, float fuzzRoughness) {
    lfx_write_slab_surface(diffuse, f0, f90, roughness, ao, worldNormal, cameraRelativePosition, anisotropy, worldTangent,
            coverage, decalResponse, secondRoughness, secondWeight, simpleCoat, coatNormalOct,
            fuzzColor, fuzzAmount, fuzzRoughness, vec3(0.0), 0.0, 0, false);
}

// Existing effect packages still use the single-slab entry without the two coat inputs.
void lfx_write_slab_surface(vec3 diffuse, vec3 f0, vec3 f90, float roughness, float ao, vec3 worldNormal,
                            vec3 cameraRelativePosition, float anisotropy, vec3 worldTangent, float coverage,
                            int decalResponse, float secondRoughness, float secondWeight,
                            vec3 fuzzColor, float fuzzAmount, float fuzzRoughness) {
    lfx_write_slab_surface(diffuse, f0, f90, roughness, ao, worldNormal, cameraRelativePosition, anisotropy, worldTangent,
            coverage, decalResponse, secondRoughness, secondWeight, 0.0, vec2(0.0), fuzzColor, fuzzAmount, fuzzRoughness);
}
#endif
