#version 330

layout(std140) uniform GaussianSplatRasterView {
    vec4 ViewToClip22_23_32_33;
    vec4 InvViewSize;
    vec4 InvDeviceZToWorldZ;
    vec4 FalloffParameters;
    vec4 DepthParameters;
    ivec4 RasterFlags;
};

uniform usamplerBuffer ShadedGaussianSplatBuffer;
#if LFX_SPLAT_SORTED
uniform usamplerBuffer SortedIndexBuffer;
#endif

out vec2 vCorner;
flat out vec4 vColor;
flat out uint vSplatIndex;

float lfx_splat_half_to_float(uint bits) {
    uint signBit = (bits & 0x8000u) << 16u;
    uint exponent = (bits >> 10u) & 31u;
    uint mantissa = bits & 1023u;
    if (exponent == 0u) {
        if (mantissa == 0u) return uintBitsToFloat(signBit);
        int normalizedExponent = -14;
        while ((mantissa & 1024u) == 0u) {
            mantissa <<= 1u;
            --normalizedExponent;
        }
        uint finalExponent = uint(normalizedExponent + 127) << 23u;
        uint finalMantissa = (mantissa & 1023u) << 13u;
        return uintBitsToFloat(signBit | finalExponent | finalMantissa);
    }
    if (exponent == 31u) {
        return uintBitsToFloat(signBit | 0x7f800000u | (mantissa << 13u));
    }
    return uintBitsToFloat(signBit | ((exponent + 112u) << 23u) | (mantissa << 13u));
}

vec2 lfx_unpack_half2(uint packedValue) {
    return vec2(lfx_splat_half_to_float(packedValue & 0xffffu), lfx_splat_half_to_float(packedValue >> 16u));
}

void main() {
    uint splatIndex =
#if LFX_SPLAT_SORTED
        texelFetch(SortedIndexBuffer, gl_InstanceID).r;
#else
        uint(gl_InstanceID);
#endif
    vSplatIndex = splatIndex;
    int base = int(splatIndex) * 6;
    float viewZ = uintBitsToFloat(texelFetch(ShadedGaussianSplatBuffer, base).r);
    vec2 centerNdc = lfx_unpack_half2(texelFetch(ShadedGaussianSplatBuffer, base + 1).r);
    vec2 axis0 = lfx_unpack_half2(texelFetch(ShadedGaussianSplatBuffer, base + 2).r) * vec2(1.0, -1.0);
    vec2 axis1 = lfx_unpack_half2(texelFetch(ShadedGaussianSplatBuffer, base + 3).r) * vec2(1.0, -1.0);
    vec2 colorRG = lfx_unpack_half2(texelFetch(ShadedGaussianSplatBuffer, base + 4).r);
    vec2 colorBA = lfx_unpack_half2(texelFetch(ShadedGaussianSplatBuffer, base + 5).r);
    vColor = vec4(colorRG, colorBA);

    float clipW = viewZ * ViewToClip22_23_32_33.y + ViewToClip22_23_32_33.w;
    float clipZ = viewZ * ViewToClip22_23_32_33.x + ViewToClip22_23_32_33.z;
    if (viewZ <= 0.0 || clipW <= 0.0 || colorBA.y < FalloffParameters.w) {
        vCorner = vec2(0.0);
        gl_Position = vec4(0.0, 0.0, -2.0, 1.0);
        return;
    }

    vec2 corner = vec2((gl_VertexID & 1) != 0 ? 1.0 : -1.0, (gl_VertexID & 2) != 0 ? 1.0 : -1.0);
    vCorner = corner;
    vec2 offsetNdc = (corner.x * 2.0 * axis0 + corner.y * 2.0 * axis1) * InvViewSize.xy * vec2(2.0, -2.0);
    vec2 clipXY = (centerNdc + offsetNdc) * clipW;
#if LFX_SPLAT_TEXTURE_Y_DOWN
    clipXY.y = -clipXY.y;
#endif
    float deviceClipZ = RasterFlags.w != 0 ? clipZ : 2.0 * clipZ - clipW;
    gl_Position = vec4(clipXY, deviceClipZ, clipW);
}
