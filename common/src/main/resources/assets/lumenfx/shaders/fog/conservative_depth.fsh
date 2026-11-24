#version 330
#moj_import <lumenfx:volumetric_common.glsl>

uniform sampler2D SceneDepthSampler;

out float fragDepth;

uint lfx_round_shift_even(uint value, int shift) {
    uint truncated = value >> uint(shift);
    uint remainder = value & ((1u << uint(shift)) - 1u);
    uint halfway = 1u << uint(shift - 1);
    uint increment = (remainder > halfway
            || (remainder == halfway && (truncated & 1u) != 0u)) ? 1u : 0u;
    return truncated + increment;
}

uint lfx_float_to_half_bits(float value) {
    uint bits = floatBitsToUint(value);
    uint sign = (bits >> 16u) & 0x8000u;
    uint exponent = (bits >> 23u) & 0xFFu;
    uint mantissa = bits & 0x7FFFFFu;
    if (exponent == 0xFFu) {
        return sign | 0x7C00u | (mantissa != 0u ? 0x0200u : 0u);
    }
    int halfExponent = int(exponent) - 127 + 15;
    if (halfExponent >= 31) {
        return sign | 0x7C00u;
    }
    if (halfExponent <= 0) {
        if (halfExponent < -10) {
            return sign;
        }
        uint significand = mantissa | 0x800000u;
        return sign | lfx_round_shift_even(significand, 14 - halfExponent);
    }
    uint rounded = lfx_round_shift_even(mantissa, 13);
    if (rounded == 0x0400u) {
        rounded = 0u;
        halfExponent++;
        if (halfExponent >= 31) {
            return sign | 0x7C00u;
        }
    }
    return sign | (uint(halfExponent) << 10u) | rounded;
}

float lfx_half_bits_to_float(uint bits) {
    uint sign = (bits & 0x8000u) << 16u;
    uint exponent = (bits >> 10u) & 0x1Fu;
    uint mantissa = bits & 0x03FFu;
    if (exponent == 0u) {
        if (mantissa == 0u) {
            return uintBitsToFloat(sign);
        }
        int shift = 0;
        uint normalized = mantissa;
        while ((normalized & 0x0400u) == 0u) {
            normalized <<= 1u;
            shift++;
        }
        normalized &= 0x03FFu;
        uint exponentBits = uint(113 - shift);
        return uintBitsToFloat(sign | (exponentBits << 23u) | (normalized << 13u));
    }
    if (exponent == 31u) {
        return uintBitsToFloat(sign | 0x7F800000u | (mantissa << 13u));
    }
    return uintBitsToFloat(sign | ((exponent + 112u) << 23u) | (mantissa << 13u));
}

float lfx_half_quantize(float value) {
    return lfx_half_bits_to_float(lfx_float_to_half_bits(value));
}

int lfx_most_significant_bit(int value) {
    if (value == 0) return -1;
    uint remaining = uint(value);
    int bit = 0;
    for (int shift = 16; shift > 0; shift >>= 1) {
        if ((remaining >> uint(shift)) != 0u) {
            remaining >>= uint(shift);
            bit += shift;
        }
    }
    return bit;
}

int lfx_hzb_source_start(int hzbIndex, int level, int viewSize) {
    if (viewSize <= 1) {
        return 0;
    }
    int sourceIndex = (hzbIndex << level) << 1;
    return int(floor(min(float(sourceIndex) + 0.25, float(viewSize) - 2.0)));
}

int lfx_hzb_source_end(int hzbIndex, int level, int viewSize) {
    if (viewSize <= 1) {
        return 0;
    }
    int lastSourceIndex = (((hzbIndex + 1) << level) - 1) << 1;
    int coordinate = int(floor(min(float(lastSourceIndex) + 0.25, float(viewSize) - 2.0)));
    return min(coordinate + 1, viewSize - 1);
}

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 gridSize = ivec2(FogGrid.xy + 0.5);
    if (cell.x < 0 || cell.y < 0 || cell.x >= gridSize.x || cell.y >= gridSize.y
            || FogProjection.w < 0.5) {
        fragDepth = 0.0;
        return;
    }

    ivec2 sourceSize = textureSize(SceneDepthSampler, 0);
    vec2 viewSize = vec2(sourceSize);
    vec2 invCulledGridSize = vec2(FogMaterial.y) / viewSize;
    vec2 tileSize = vec2(2.0, -2.0) * invCulledGridSize;
    vec2 unitPlaneMin = vec2(-1.0, 1.0);
    vec2 tileMin = (vec2(cell) - 0.5) * tileSize + unitPlaneMin;
    vec2 tileMax = (vec2(cell) + 1.5) * tileSize + unitPlaneMin;
    vec2 cullMin = min(tileMin, tileMax);
    vec2 cullMax = max(tileMin, tileMax);

    float u0 = clamp(cullMin.x * 0.5 + 0.5, 0.0, 1.0);
    float v0 = clamp(cullMax.y * -0.5 + 0.5, 0.0, 1.0);
    float u1 = clamp(cullMax.x * 0.5 + 0.5, 0.0, 1.0);
    float v1 = clamp(cullMin.y * -0.5 + 0.5, 0.0, 1.0);

    ivec2 pixelMin = ivec2(floor(vec2(u0, v0) * viewSize + vec2(0.5)));
    ivec2 pixelMax = ivec2(floor(vec2(u1, v1) * viewSize - vec2(0.5)));
    ivec2 lastPixel = sourceSize - 1;
    pixelMin = max(pixelMin, ivec2(0));
    pixelMax = min(pixelMax, lastPixel);
    ivec2 hzbMin = pixelMin >> 1;
    ivec2 hzbMax = max(pixelMin, pixelMax) >> 1;

    int mipX = lfx_most_significant_bit(hzbMax.x - hzbMin.x);
    int mipY = lfx_most_significant_bit(hzbMax.y - hzbMin.y);
    int mipLevel = max(max(mipX, mipY) - 1, 0);
    int maxPixelOffset = 3;
    if ((hzbMax.x >> mipLevel) - (hzbMin.x >> mipLevel) > maxPixelOffset
            || (hzbMax.y >> mipLevel) - (hzbMin.y >> mipLevel) > maxPixelOffset) {
        mipLevel++;
    }

    ivec2 hzbStart = hzbMin >> mipLevel;
    ivec2 hzbEnd = hzbMax >> mipLevel;
    ivec2 hzbSampleEnd = min(hzbStart + ivec2(3), hzbEnd);

    int sourceX0 = lfx_hzb_source_start(hzbStart.x, mipLevel, sourceSize.x);
    int sourceX1 = lfx_hzb_source_end(hzbSampleEnd.x, mipLevel, sourceSize.x);
    int sourceY0 = lfx_hzb_source_start(hzbStart.y, mipLevel, sourceSize.y);
    int sourceY1 = lfx_hzb_source_end(hzbSampleEnd.y, mipLevel, sourceSize.y);

    float minDeviceZ = 1.0;
    for (int y = sourceY0; y <= sourceY1 && minDeviceZ > 0.0; y++) {
        int physicalY = sourceSize.y - 1 - y;
        for (int x = sourceX0; x <= sourceX1 && minDeviceZ > 0.0; x++) {
            minDeviceZ = min(minDeviceZ, texelFetch(SceneDepthSampler, ivec2(x, physicalY), 0).r);
        }
    }
    fragDepth = lfx_half_quantize(minDeviceZ);
}
