#moj_import <lumenfx:substrate/wrap_encoding.glsl>
#moj_import <lumenfx:lighting/clear_coat_normals.glsl>

const int LFX_ADAPTIVE_FIRST_CLOSURE = 3;
const int LFX_ADAPTIVE_FIRST_DIFFUSION_CLOSURE = 5;
const uint LFX_ADAPTIVE_ANISOTROPY = 16u;
const uint LFX_ADAPTIVE_EDGE = 32u;
const uint LFX_ADAPTIVE_HAZE = 64u;
const uint LFX_ADAPTIVE_FUZZ = 128u;
const uint LFX_ADAPTIVE_WRAP = 256u;
const uint LFX_ADAPTIVE_VOLUME = 512u;
const uint LFX_ADAPTIVE_SPECULAR_PROFILE = 1024u;
const uint LFX_ADAPTIVE_ABOVE = 2048u;
const uint LFX_ADAPTIVE_PRECISE_COLOR = 16384u;
const uint LFX_ADAPTIVE_COLOR_MASK = 1048575u;
const uint LFX_ADAPTIVE_GREY_WEIGHT = 1u << 28;
const uint LFX_ADAPTIVE_HAS_DIFFUSION = 1u << 29;
const uint LFX_ADAPTIVE_DIFFUSION_PROFILE = 1u << 30;
const uint LFX_ADAPTIVE_DIFFUSION_THIN = 1u << 31;
const uint LFX_ADAPTIVE_TOP_PRECISE_NORMAL = 1u << 10;
const uint LFX_ADAPTIVE_DIFFUSION_LAYOUT = 1u << 11;
const uint LFX_ADAPTIVE_DIFFUSION_PRESENT = 1u << 12;
const uint LFX_ADAPTIVE_DIFFUSION_IS_PROFILE = 1u << 13;
const uint LFX_ADAPTIVE_DIFFUSION_PRECISE_COLOR = 1u << 14;

struct LfxSlabDiffusionPixel {
    int type;
    uint profileData;
    vec3 meanFreePath;
    vec3 baseColor;
};

LfxSlabDiffusionPixel lfx_slab_diffusion_pixel() {
    return LfxSlabDiffusionPixel(0, 0u, vec3(0.0), vec3(0.0));
}

uint lfx_adaptive_pack_diffusion_profile(int index, float scale, float thickness) {
    return uint(index) | (lfx_substrate_pack8(scale) << 8) | ((lfx_wrap_half(clamp(thickness, 0.0, 64512.0)) >> 5) << 16);
}

int lfx_adaptive_profile_id(uint data) { return int(data & 255u); }
float lfx_adaptive_profile_scale(uint data) { return float((data >> 8) & 255u) / 255.0; }
float lfx_adaptive_profile_thickness(uint data) { return lfx_wrap_unpack_half((data >> 16) << 5); }

uint lfx_adaptive_pack_diffusion_mfp(vec3 mfp) {
    mfp = clamp(mfp, 0.0, 64512.0);
    uint r = lfx_wrap_half(mfp.x) >> 5;
    uint g = lfx_wrap_half(mfp.y) >> 5;
    uint b = lfx_wrap_half(mfp.z) >> 5;
    return (r << 22) | (g << 12) | ((b & 1022u) << 2);
}

vec3 lfx_adaptive_unpack_diffusion_mfp(uint data) {
    return vec3(lfx_wrap_unpack_half((data >> 22) << 5),
            lfx_wrap_unpack_half(((data >> 12) & 1023u) << 5),
            lfx_wrap_unpack_half(((data >> 2) & 1022u) << 5));
}

uint lfx_adaptive_pack_color8(vec3 value, bool precise) {
    value = clamp(value, 0.0, 1.0);
    vec3 encoded = precise ? linearToSrgb(value) : lfx_substrate_sqrt_fast(value);
    uvec3 bytes = uvec3(clamp(encoded, 0.0, 1.0) * 255.0);
    return bytes.x | (bytes.y << 8) | (bytes.z << 16);
}

vec3 lfx_adaptive_unpack_color8(uint packedValue, bool precise) {
    vec3 encoded = vec3(packedValue & 255u, (packedValue >> 8) & 255u, (packedValue >> 16) & 255u) / 255.0;
    return precise ? srgbToLinear(encoded) : encoded * encoded;
}

uint lfx_adaptive_pack_unsigned(vec3 value) {
    value = clamp(value, vec3(0.0), vec3(65024.0, 65024.0, 64512.0));
    uvec3 halves = uvec3(lfx_wrap_half(value.x), lfx_wrap_half(value.y), lfx_wrap_half(value.z));
    return ((halves.x >> 4) << 21) | ((halves.y >> 4) << 10) | (halves.z >> 5);
}

vec3 lfx_adaptive_unpack_unsigned(uint packedValue) {
    return vec3(lfx_wrap_unpack_half((packedValue >> 21) << 4),
            lfx_wrap_unpack_half(((packedValue >> 10) & 2047u) << 4),
            lfx_wrap_unpack_half((packedValue & 1023u) << 5));
}

uint lfx_adaptive_pack_normal(vec3 normal) {
    vec2 oct = lfx_clear_coat_octahedron(normal);
    uvec2 halfOct = uvec2(lfx_wrap_half(abs(oct.x)), lfx_wrap_half(abs(oct.y)));
    halfOct |= (floatBitsToUint(oct) >> 16) & 32768u;
    return halfOct.x | (halfOct.y << 16);
}

vec3 lfx_adaptive_unpack_normal(uint packedValue) {
    uvec2 halfOct = uvec2(packedValue & 65535u, packedValue >> 16);
    vec2 oct = vec2(lfx_wrap_unpack_half(halfOct.x & 32767u), lfx_wrap_unpack_half(halfOct.y & 32767u));
    return lfx_clear_coat_unit_vector(uintBitsToFloat(floatBitsToUint(oct) | ((halfOct & 32768u) << 16)));
}
