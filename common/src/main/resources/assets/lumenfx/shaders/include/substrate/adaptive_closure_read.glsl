#moj_import <lumenfx:substrate/adaptive_closure_encoding.glsl>
#moj_import <lumenfx:substrate/slab_basis.glsl>
#moj_import <lumenfx:substrate/adaptive_header.glsl>

struct LfxAdaptiveSlab {
    vec3 normal;
    vec3 baseNormal;
    vec4 tangentAnisotropy;
    bool anisotropic;
    vec3 diffuse;
    vec4 specular;
    vec3 edge;
    vec2 haze;
    bool simpleCoat;
    vec3 luminance;
    float coverageAbove;
    vec3 transmissionAbove;
    vec3 fuzzColor;
    vec2 fuzz;
    vec3 wrapMfp;
    vec3 volumeMfp;
    float phase;
    int wrap;
    float specularProfile;
    uint diffusionData;
    bool diffusion;
    bool diffusionProfile;
    bool thin;
};

LfxAdaptiveSlab lfx_read_adaptive_slab(ivec2 pixel, inout int offset, mat3 toView) {
    int start = offset;
    uint flags = lfx_adaptive_word(pixel, offset++);
    bool precise = (flags & LFX_ADAPTIVE_PRECISE_COLOR) != 0u;
    uint basis = lfx_adaptive_word(pixel, offset++);
    uint baseColor = lfx_adaptive_word(pixel, offset++);
    uint reflection = lfx_adaptive_word(pixel, offset++);
    uint weight = lfx_adaptive_word(pixel, offset++);
    LfxAdaptiveSlab slab;
    slab.diffuse = lfx_substrate_unpack_color20(baseColor, precise);
    slab.specular = vec4(lfx_substrate_unpack_color20(reflection & LFX_ADAPTIVE_COLOR_MASK, precise),
            float((reflection >> 20) & 255u) / 255.0);
    slab.luminance = (reflection & LFX_ADAPTIVE_GREY_WEIGHT) != 0u
            ? vec3(lfx_wrap_unpack_half(weight << 5)) : lfx_adaptive_unpack_unsigned(weight);
    slab.edge = (flags & LFX_ADAPTIVE_EDGE) != 0u
            ? lfx_substrate_unpack_edge_color(lfx_adaptive_word(pixel, offset++)) : vec3(1.0);
    uint haze = (flags & LFX_ADAPTIVE_HAZE) != 0u ? lfx_adaptive_word(pixel, offset++) : 0u;
    uint fuzz = (flags & LFX_ADAPTIVE_FUZZ) != 0u ? lfx_adaptive_word(pixel, offset++) : 0u;
    slab.wrapMfp = (flags & LFX_ADAPTIVE_WRAP) != 0u ? lfx_adaptive_unpack_unsigned(lfx_adaptive_word(pixel, offset++)) * 0.01 : vec3(0.0);
    slab.volumeMfp = (flags & LFX_ADAPTIVE_VOLUME) != 0u ? lfx_adaptive_unpack_unsigned(lfx_adaptive_word(pixel, offset++)) * 0.01 : vec3(0.0);
    slab.specularProfile = (flags & LFX_ADAPTIVE_SPECULAR_PROFILE) != 0u ? float(lfx_adaptive_word(pixel, offset++)) - 255.0 : 0.0;
    uint above = (flags & LFX_ADAPTIVE_ABOVE) != 0u ? lfx_adaptive_word(pixel, offset++) : 0u;
    slab.diffusion = (reflection & LFX_ADAPTIVE_HAS_DIFFUSION) != 0u;
    slab.diffusionProfile = (reflection & LFX_ADAPTIVE_DIFFUSION_PROFILE) != 0u;
    slab.thin = (reflection & LFX_ADAPTIVE_DIFFUSION_THIN) != 0u;
    slab.diffusionData = slab.diffusion ? lfx_adaptive_word(pixel, offset++) : 0u;
    slab.coverageAbove = float(above >> 24) / 255.0;
    slab.transmissionAbove = slab.coverageAbove > 0.0 ? lfx_adaptive_unpack_color8(above, precise) : vec3(1.0);
    slab.wrap = any(greaterThan(slab.wrapMfp, vec3(0.0))) ? int((flags >> 12) & 3u) : 0;
    slab.phase = float(flags >> 24) * (2.0 / 255.0) - 1.0;
    slab.haze = vec2(float(haze & 255u) / 255.0, float((haze >> 8) & 127u) / 127.0);
    slab.simpleCoat = (haze & 32768u) != 0u;
    slab.fuzzColor = lfx_substrate_unpack_color20(fuzz & LFX_ADAPTIVE_COLOR_MASK, precise);
    slab.fuzz = vec2(float((fuzz >> 20) & 63u), float(fuzz >> 26)) / 63.0;

    vec3 normal, tangent;
    slab.anisotropic = (flags & 32768u) != 0u;
    slab.tangentAnisotropy = vec4(0.0);
    if (slab.anisotropic) {
        lfx_substrate_unpack_normal_tangent(vec4(float(basis & 65535u), float(basis >> 16), 0.0, 0.0), normal, tangent);
        slab.tangentAnisotropy = vec4(normalize(toView * tangent.xzy), float((flags >> 16) & 255u) * (2.0 / 255.0) - 1.0);
    } else normal = lfx_adaptive_unpack_normal(basis);
    slab.normal = normalize(toView * normal.xzy);
    slab.baseNormal = slab.normal;
#ifdef LFX_CLEAR_COAT_BOTTOM_NORMAL
    uint coat = haze >> 16;
    if (slab.simpleCoat && coat != 0u)
        slab.baseNormal = normalize(toView * lfx_decode_slab_coat_normal(coat, normal).xzy);
#endif
    offset = start + int(flags & 15u);
    return slab;
}

vec3 lfx_adaptive_luminance(LfxAdaptiveSlab slab, vec3 light) {
    vec3 refracted = refract(-light, slab.normal, mix(1.0, 1.0 / 1.5, slab.coverageAbove));
    float noL = clamp(dot(slab.normal, -refracted), 0.0, 1.0);
    vec3 attenuation = pow(max(slab.transmissionAbove, vec3(0.0001)), vec3(1.0 / max(noL, 0.001)));
    return slab.luminance * mix(vec3(1.0), attenuation, slab.coverageAbove);
}
