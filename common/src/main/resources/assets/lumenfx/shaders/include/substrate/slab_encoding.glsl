#moj_import <lumenfx:srgb.glsl>

// Substrate.ush PackR7G7B6Gamma2, PackFuzz and the authored F90 chroma payload.
vec3 lfx_substrate_sqrt_fast(vec3 value) {
    return intBitsToFloat(ivec3(0x1FBD1DF5) + (floatBitsToInt(value) >> 1));
}

uint lfx_substrate_pack_color20(vec3 value, float dither, bool preciseColor) {
    const vec3 scale = vec3(127.0, 127.0, 63.0);
    value = clamp(value, 0.0, 1.0);
    vec3 encoded = preciseColor ? linearToSrgb(value) : lfx_substrate_sqrt_fast(value);
    vec3 quantized = clamp(encoded + (dither - 0.5) * vec3(notEqual(encoded, vec3(0.0))) / scale, 0.0, 1.0);
    uvec3 packedValue = uvec3(quantized * scale);
    return packedValue.r | (packedValue.g << 7) | (packedValue.b << 14);
}

vec3 lfx_substrate_unpack_color20(uint packedValue, bool preciseColor) {
    vec3 encoded = vec3(packedValue & 127u, (packedValue >> 7) & 127u, (packedValue >> 14) & 63u)
        / vec3(127.0, 127.0, 63.0);
    return preciseColor ? srgbToLinear(encoded) : encoded * encoded;
}

const uint LFX_SLAB_PROFILE_PAYLOAD = 1u << 22;
const uint LFX_SLAB_PROFILE_PRECISE_FUZZ = 1u << 21;

float lfx_slab_profile_id(vec4 source) {
    return (uint(source.a) & LFX_SLAB_PROFILE_PAYLOAD) != 0u ? source.g : 0.0;
}

vec3 lfx_slab_fuzz_color(vec4 source) {
    uint flags = uint(source.a);
    if ((flags & LFX_SLAB_PROFILE_PAYLOAD) == 0u) return source.rgb;
    return lfx_substrate_unpack_color20(uint(source.r), (flags & LFX_SLAB_PROFILE_PRECISE_FUZZ) != 0u);
}

uint lfx_substrate_pack6(float value, float dither) {
    value = clamp(value, 0.0, 1.0);
    return uint(clamp(value + (dither - 0.5) * float(value != 0.0) / 63.0, 0.0, 1.0) * 63.0);
}

uint lfx_substrate_pack8(float value) {
    return uint(clamp(value, 0.0, 1.0) * 255.0);
}

uint lfx_substrate_pack_edge_color(vec3 edgeColor) {
    if (all(greaterThanEqual(edgeColor, vec3(1.0)))) return 65536u;
    edgeColor = clamp(edgeColor, 0.0, 1.0);
    float peak = max(edgeColor.r, max(edgeColor.g, edgeColor.b));
    vec3 normalized = peak > 0.0 ? edgeColor / peak : vec3(1.0);
    float co = dot(normalized, vec3(2.0, 0.0, -2.0)) * 0.25 + 0.5;
    float cg = dot(normalized, vec3(-1.0, 2.0, -1.0)) * 0.25 + 0.5;
    return lfx_substrate_pack8(co) | (lfx_substrate_pack8(cg) << 8);
}

vec3 lfx_substrate_unpack_edge_color(uint edgeColor) {
    if (edgeColor == 65536u) return vec3(1.0);
    float co = float(edgeColor & 255u) / 255.0 - 0.5;
    float cg = float((edgeColor >> 8) & 255u) / 255.0 - 0.5;
    vec3 recovered = clamp(vec3(1.0 / 3.0) + vec3(co - cg, cg, -co - cg), 0.0, 1.0);
    float peak = max(recovered.r, max(recovered.g, recovered.b));
    return peak > 0.0 ? recovered / peak : recovered;
}

float lfx_substrate_round_to_half(float value) {
    uint bits = floatBitsToUint(value);
    uint magnitude = bits & 0x7fffffffu;
    float rounded = magnitude < 0x38800000u
        ? roundEven(abs(value) * 16777216.0) * (1.0 / 16777216.0)
        : uintBitsToFloat((magnitude + 0xfffu + ((magnitude >> 13) & 1u)) & 0xffffe000u);
    return uintBitsToFloat(floatBitsToUint(rounded) | (bits & 0x80000000u));
}
