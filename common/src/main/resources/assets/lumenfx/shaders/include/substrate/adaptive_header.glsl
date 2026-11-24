#moj_import <lumenfx:substrate/adaptive_closure_encoding.glsl>

uniform usampler2DArray LfxAdaptiveParticleWords;

uint lfx_adaptive_word(ivec2 pixel, int word) {
    return texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, word >> 2), 0)[word & 3];
}

vec3 lfx_adaptive_top_normal(uvec4 header) {
    if ((header.z & LFX_ADAPTIVE_TOP_PRECISE_NORMAL) != 0u) return lfx_adaptive_unpack_normal(header.y);
    ivec2 oct = ivec2(header.y & 2047u, (header.y >> 11) & 2047u) - 1024;
    return lfx_clear_coat_unit_vector(vec2(oct) / 1023.0);
}

int lfx_adaptive_first_closure(uvec4 header) {
    return (header.z & LFX_ADAPTIVE_DIFFUSION_LAYOUT) == 0u
        ? LFX_ADAPTIVE_FIRST_CLOSURE : LFX_ADAPTIVE_FIRST_DIFFUSION_CLOSURE;
}

LfxSlabDiffusionPixel lfx_adaptive_diffusion_pixel(ivec2 pixel, uvec4 header) {
    LfxSlabDiffusionPixel result = lfx_slab_diffusion_pixel();
    if (header.x == 0u || (header.z & LFX_ADAPTIVE_DIFFUSION_PRESENT) == 0u) return result;
    result.baseColor = lfx_adaptive_unpack_color8(header.w, (header.z & LFX_ADAPTIVE_DIFFUSION_PRECISE_COLOR) != 0u);
    uint data = lfx_adaptive_word(pixel, 4);
    if ((header.z & LFX_ADAPTIVE_DIFFUSION_IS_PROFILE) != 0u) {
        result.type = 2;
        result.profileData = data;
    } else {
        result.type = 1;
        result.meanFreePath = lfx_adaptive_unpack_diffusion_mfp(data);
    }
    return result;
}
