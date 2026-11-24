#moj_import <lumenfx:substrate/slab_encoding.glsl>

uint lfx_wrap_magnitude(float field) {
    return uint(field < 0.0 ? -field - 1.0 : field);
}

uint lfx_wrap_half(float value) {
    float rounded = lfx_substrate_round_to_half(value);
    uint bits = floatBitsToUint(rounded);
    uint exponent = (bits >> 23) & 255u;
    return exponent < 113u ? uint(rounded * 16777216.0) : ((exponent - 112u) << 10) | ((bits >> 13) & 1023u);
}

float lfx_wrap_unpack_half(uint bits) {
    uint exponent = bits >> 10;
    uint mantissa = bits & 1023u;
    return exponent == 0u ? float(mantissa) * (1.0 / 16777216.0)
        : uintBitsToFloat(((exponent + 112u) << 23) | (mantissa << 13));
}

// The three signed float fields hold exact 24-bit integers. Signs carry the MFP high bit, encoding, and wrap type.
vec3 lfx_pack_slab_wrap(uint edge, uint coat, vec3 mfp, float phase, int type, bool single) {
    mfp = clamp(mfp, vec3(0.0), single ? vec3(64512.0) : vec3(65024.0, 65024.0, 64512.0));
    uvec3 halves = uvec3(lfx_wrap_half(mfp.x), lfx_wrap_half(mfp.y), lfx_wrap_half(mfp.z));
    uint packedValue = single ? ((halves.x >> 5) << 20) | ((halves.y >> 5) << 10) | (halves.z >> 5)
        : ((halves.x >> 4) << 21) | ((halves.y >> 4) << 10) | (halves.z >> 5);
    if (type == 0 || packedValue == 0u) return vec3(float(edge), float(coat), 0.0);
    uint opacity = single ? uint(clamp(1.0 - abs(phase), 0.0, 1.0) * 127.0) : lfx_substrate_pack8(phase * 0.5 + 0.5);
    float r = float(edge | (((packedValue >> 24) & 127u) << 17));
    float g = float(coat | (opacity << 16));
    float b = float(packedValue & 16777215u);
    return vec3((packedValue & 0x80000000u) != 0u ? -r - 1.0 : r, type == 2 ? -g - 1.0 : g, single ? -b - 1.0 : b);
}

uint lfx_slab_wrap_bits(vec3 data) {
    return ((lfx_wrap_magnitude(data.r) >> 17) << 24) | lfx_wrap_magnitude(data.b) | (data.r < 0.0 ? 0x80000000u : 0u);
}

float lfx_slab_wrap_opacity(vec3 data) {
    float phase = float(lfx_wrap_magnitude(data.g) >> 16);
    return data.b < 0.0 ? phase / 127.0 : 1.0 - abs(phase * (2.0 / 255.0) - 1.0);
}

vec3 lfx_slab_wrap_mfp_meters(vec3 data) {
    uint bits = lfx_slab_wrap_bits(data);
    uvec3 halves = data.b < 0.0 ? uvec3((bits >> 20) << 5, ((bits >> 10) & 1023u) << 5, (bits & 1023u) << 5)
        : uvec3((bits >> 21) << 4, ((bits >> 10) & 2047u) << 4, (bits & 1023u) << 5);
    return vec3(lfx_wrap_unpack_half(halves.x), lfx_wrap_unpack_half(halves.y), lfx_wrap_unpack_half(halves.z)) * 0.01;
}

vec3 lfx_slab_wrap_transmittance(vec3 data) {
    return exp(-max(vec3(1e-6), 1.0 / max(lfx_slab_wrap_mfp_meters(data), vec3(1e-12))));
}
