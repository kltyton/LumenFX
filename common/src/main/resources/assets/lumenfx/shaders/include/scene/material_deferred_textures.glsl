#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:substrate/slab_encoding.glsl>
#moj_import <lumenfx:substrate/wrap_encoding.glsl>

vec2 lfx_deferred_scene_host_uv(vec2 uv) {
    return vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y);
}

#if (LFX_DEFERRED_SCENE_TEXTURES & 1) != 0
uniform sampler2D LfxMaterialGBufferA;
vec3 lfx_gbuffer_normal(vec2 uv) {
    return texture(LfxMaterialGBufferA, uv).rgb * 2.0 - 1.0;
}
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 64) != 0
uniform sampler2D LfxMaterialGBufferD;
vec4 lfx_gbuffer_custom(vec2 uv) { return texture(LfxMaterialGBufferD, uv); }
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 2) != 0
uniform sampler2D LfxMaterialGBufferB;
vec4 lfx_gbuffer_b(vec2 uv) { return texture(LfxMaterialGBufferB, uv); }
uint lfx_gbuffer_model_and_flags(vec2 uv) { return uint(round(lfx_gbuffer_b(uv).a * 255.0)); }
bool lfx_gbuffer_is_slab(vec2 uv) { return int(lfx_gbuffer_model_and_flags(uv) & 15u) == LFX_SHADING_MODEL_SUBSTRATE; }
bool lfx_gbuffer_is_substrate_hair(vec2 uv) { return int(lfx_gbuffer_model_and_flags(uv) & 15u) == LFX_SHADING_MODEL_SUBSTRATE_HAIR; }
bool lfx_gbuffer_is_substrate_eye(vec2 uv) { return int(lfx_gbuffer_model_and_flags(uv) & 15u) == LFX_SHADING_MODEL_SUBSTRATE_EYE; }
bool lfx_gbuffer_is_substrate_toon(vec2 uv) { return int(lfx_gbuffer_model_and_flags(uv) & 15u) == LFX_SHADING_MODEL_SUBSTRATE_TOON; }
bool lfx_gbuffer_is_substrate(vec2 uv) {
    return lfx_gbuffer_is_slab(uv) || lfx_gbuffer_is_substrate_hair(uv) || lfx_gbuffer_is_substrate_eye(uv) || lfx_gbuffer_is_substrate_toon(uv);
}
#if (LFX_DEFERRED_SCENE_TEXTURES & 512) != 0
uniform sampler2D LfxMaterialParticleSource;
vec4 lfx_gbuffer_particle_source(vec2 uv) { return texture(LfxMaterialParticleSource, uv); }
#endif
float lfx_gbuffer_shading_model(vec2 uv) {
    if (lfx_gbuffer_is_substrate_hair(uv)) return float(LFX_SHADING_MODEL_HAIR);
    if (lfx_gbuffer_is_substrate_eye(uv)) return float(LFX_SHADING_MODEL_EYE);
    if (lfx_gbuffer_is_substrate_toon(uv)) return 13.0;
#if (LFX_DEFERRED_SCENE_TEXTURES & 512) != 0
    if (lfx_gbuffer_is_slab(uv)) {
        uint source = uint(lfx_gbuffer_particle_source(uv).a);
        uint wrap = (source >> 18) & 3u;
        if (wrap == 1u) return float(LFX_SHADING_MODEL_SUBSURFACE);
        if (wrap == 2u) return float(LFX_SHADING_MODEL_TWOSIDED_FOLIAGE);
        if ((source & (1u << 17)) != 0u) return float(LFX_SHADING_MODEL_CLOTH);
        return (source & (1u << 20)) != 0u ? float(LFX_SHADING_MODEL_CLEAR_COAT) : 1.0;
    }
#endif
    return float(lfx_gbuffer_model_and_flags(uv) & 15u);
}
float lfx_gbuffer_first_person(vec2 uv) {
    return (lfx_gbuffer_model_and_flags(uv) & 64u) != 0u ? 1.0 : 0.0;
}
vec3 lfx_gbuffer_shading_model_color(vec2 uv) {
    switch (int(lfx_gbuffer_shading_model(uv))) {
        case 0: return vec3(0.1, 0.1, 0.2);
        case 1: return vec3(0.1, 1.0, 0.1);
        case 2: return vec3(1.0, 0.1, 0.1);
        case 3: return vec3(0.6, 0.4, 0.1);
        case 4: return vec3(0.1, 0.4, 0.4);
        case 5: return vec3(0.2, 0.6, 0.5);
        case 6: return vec3(0.2, 0.2, 0.8);
        case 7: return vec3(0.6, 0.1, 0.5);
        case 8: return vec3(0.7, 1.0, 1.0);
        case 9: return vec3(0.3, 1.0, 1.0);
        case 10: return vec3(0.5, 0.5, 1.0);
        case 11: return vec3(1.0, 0.8, 0.3);
        case 12: return vec3(1.0, 1.0, 0.0);
        default: return vec3(1.0);
    }
}
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 66) == 66
float lfx_gbuffer_toon_shading_id(vec2 uv) {
    return lfx_gbuffer_is_substrate_toon(uv) ? lfx_gbuffer_custom(uv).a : -1.0;
}

vec4 lfx_gbuffer_subsurface(vec2 uv) {
    if (lfx_gbuffer_is_substrate_hair(uv) || lfx_gbuffer_is_substrate_toon(uv)) return vec4(0.0);
#if (LFX_DEFERRED_SCENE_TEXTURES & 512) != 0
    if (lfx_gbuffer_is_substrate_eye(uv)) return vec4(lfx_gbuffer_particle_source(uv).rgb, 0.0);
    if (lfx_gbuffer_is_slab(uv)) {
        vec4 source = lfx_gbuffer_particle_source(uv);
        vec3 data = lfx_gbuffer_custom(uv).rgb;
        if (lfx_slab_wrap_bits(data) != 0u) return vec4(exp(-1.0 / max(vec3(1e-10), lfx_slab_wrap_mfp_meters(data))), 0.0);
        return (uint(source.a) & (1u << 17)) != 0u ? vec4(lfx_slab_fuzz_color(source), 0.0) : vec4(0.0);
    }
#endif
    vec4 custom = lfx_gbuffer_custom(uv);
    int model = int(lfx_gbuffer_shading_model(uv));
    if (model == 2 || model == 3 || model == 5 || model == 6 || model == 7 || model == 9 || model == 13)
        custom.rgb *= custom.rgb;
    return custom;
}

float lfx_gbuffer_opacity(vec2 uv) {
    if (lfx_gbuffer_is_substrate_hair(uv)) return lfx_gbuffer_custom(uv).z;
    if (lfx_gbuffer_is_substrate_toon(uv)) return 0.0;
#if (LFX_DEFERRED_SCENE_TEXTURES & 512) != 0
    if (lfx_gbuffer_is_substrate_eye(uv)) return 1.0 - float(uint(lfx_gbuffer_particle_source(uv).a) & 255u) / 255.0;
    if (lfx_gbuffer_is_slab(uv)) {
        vec4 custom = lfx_gbuffer_custom(uv);
        if (lfx_slab_wrap_bits(custom.rgb) != 0u) return lfx_slab_wrap_opacity(custom.rgb);
        uint source = uint(lfx_gbuffer_particle_source(uv).a);
        if ((source & (1u << 17)) != 0u) return float(source & 63u) / 63.0;
        uint haze = uint(custom.a);
        return (haze & 32768u) != 0u ? float((haze >> 8) & 127u) / 127.0 : 0.0;
    }
#endif
    return lfx_gbuffer_custom(uv).a;
}
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 4) != 0
uniform sampler2D LfxMaterialGBufferC;
vec4 lfx_gbuffer_c(vec2 uv) { return texture(LfxMaterialGBufferC, uv); }
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 6) == 6
#if (LFX_DEFERRED_SCENE_TEXTURES & 128) != 0
uniform sampler2D LfxMaterialParticleDiffuse;
#endif
#if (LFX_DEFERRED_SCENE_TEXTURES & 256) != 0
uniform sampler2D LfxMaterialParticleF0;
#endif
vec3 lfx_gbuffer_diffuse(vec2 uv) {
#if (LFX_DEFERRED_SCENE_TEXTURES & 128) != 0
    if (lfx_gbuffer_is_substrate(uv)) return srgbToLinear(texture(LfxMaterialParticleDiffuse, uv).rgb);
#endif
    vec3 baseColor = lfx_gbuffer_c(uv).rgb;
    return baseColor - baseColor * lfx_gbuffer_b(uv).r;
}
vec3 lfx_gbuffer_specular_color(vec2 uv) {
#if (LFX_DEFERRED_SCENE_TEXTURES & 256) != 0
    if (lfx_gbuffer_is_substrate(uv)) return texture(LfxMaterialParticleF0, uv).rgb;
#endif
    vec2 metallicSpecular = lfx_gbuffer_b(uv).rg;
    return mix(vec3(0.08 * metallicSpecular.y), lfx_gbuffer_c(uv).rgb, metallicSpecular.x);
}
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 8) != 0
uniform sampler2D LfxMaterialRawSceneColor;
vec3 lfx_deferred_scene_color(vec2 uv) { return texture(LfxMaterialRawSceneColor, uv).rgb; }
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 16) != 0
uniform sampler2D LfxMaterialSceneAO;
float lfx_deferred_scene_ao(vec2 uv) { return texture(LfxMaterialSceneAO, uv).r; }
#endif

#if (LFX_DEFERRED_SCENE_TEXTURES & 34) == 34
uniform sampler2D LfxMaterialGBufferF;
#if (LFX_DEFERRED_SCENE_TEXTURES & 1) != 0
#moj_import <lumenfx:substrate/slab_basis.glsl>
#endif
vec3 lfx_gbuffer_tangent(vec2 uv) {
    if ((lfx_gbuffer_model_and_flags(uv) & 16u) != 0u) return texture(LfxMaterialGBufferF, uv).rgb * 2.0 - 1.0;
#if (LFX_DEFERRED_SCENE_TEXTURES & 1) != 0
    if (lfx_gbuffer_is_substrate(uv))
        return lfx_substrate_tangent_basis(normalize(lfx_gbuffer_normal(uv)))[0];
#endif
    return vec3(0.0);
}
float lfx_gbuffer_anisotropy(vec2 uv) {
    return (lfx_gbuffer_model_and_flags(uv) & 16u) != 0u ? texture(LfxMaterialGBufferF, uv).a * 2.0 - 1.0 : 0.0;
}
#endif
