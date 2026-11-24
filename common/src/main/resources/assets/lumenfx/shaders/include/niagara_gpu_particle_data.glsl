#ifndef LFX_GPU_PARTICLE_DATA
#define LFX_GPU_PARTICLE_DATA
layout(std140) uniform NiagaraParticleData {
    uvec4 Attribute0;
    uvec4 Attribute1;
    uvec4 Attribute2;
    uvec4 Flags;
    vec4 WorldOffset;
    vec4 OwnerOffset;
    vec4 OwnerRotation;
    vec4 Tint;
    vec4 Atlas;
    vec4 HostUVScale;
    vec4 HostDynamic;
    vec4 ClockScale;
    vec4 HostPosition;
    vec4 HostSizeRotation;
    vec4 HostColor;
    vec4 HostAgeRandom;
    uvec4 ExtraAttributes;
    vec4 HostExtra1;
    vec4 HostExtra2;
    vec4 HostExtra3;
    uvec4 AxisAttributes;
    uvec4 OrientationInfo;
    vec4 HostVelocity;
    vec4 HostFacing;
    vec4 HostAlignment;
    vec4 HostOffsetPivot;
    vec4 FacingBlend;
    vec4 OwnerScale;
    vec4 PreviousOwnerScale;
    vec4 OwnerRelative0;
    vec4 OwnerRelative1;
    vec4 OwnerRelative2;
    vec4 OwnerRelative3;
    vec4 OwnerInverseRelative0;
    vec4 OwnerInverseRelative1;
    vec4 OwnerInverseRelative2;
    vec4 OwnerInverseRelative3;
    vec4 PreviousOwnerRelative0;
    vec4 PreviousOwnerRelative1;
    vec4 PreviousOwnerRelative2;
    vec4 PreviousOwnerRelative3;
    vec4 OwnerPositionHigh;
    vec4 OwnerInverseScale;
    vec4 SimulationTile;
    vec4 ComponentPreViewTile;
    vec4 ComponentPreViewOffset;
    vec4 OwnerWorldToPrevious0;
    vec4 OwnerWorldToPrevious1;
    vec4 OwnerWorldToPrevious2;
    vec4 OwnerWorldToPrevious3;
#if LFX_MESH_NIAGARA
    uvec4 MeshAttributes;
    vec4 HostMeshScale;
    vec4 HostMeshOrientation;
    uvec4 MeshPreviousAttributes;
    uvec4 MeshPreviousAxis;
    vec4 HostPreviousPosition;
    vec4 HostPreviousScaleOffset;
    vec4 HostPreviousOrientation;
    vec4 HostPreviousVelocity;
    vec4 MeshPreviousOwnerPositionScale;
    vec4 MeshPreviousOwnerRotation;
#else
    uvec4 SpritePreviousAttributes;
    uvec4 SpritePreviousAxis;
    vec4 SpriteHostPreviousPosition;
    vec4 SpriteHostPreviousSizeRotation;
    vec4 SpriteHostPreviousVelocity;
    vec4 SpriteHostPreviousFacing;
    vec4 SpriteHostPreviousAlignment;
    vec4 SpritePreviousOwnerPositionScale;
    vec4 SpritePreviousOwnerRotation;
#endif
};
uniform samplerBuffer NiagaraFloatData;
uniform usamplerBuffer NiagaraHalfData;
uniform isamplerBuffer NiagaraSortedIndices;

float lfx_half_to_float(uint value) {
    uint signBit = (value & 0x8000u) << 16;
    uint exponent = (value >> 10) & 31u;
    uint mantissa = value & 1023u;
    if (exponent == 0u) {
        if (mantissa == 0u) return uintBitsToFloat(signBit);
        int normalizedExponent = -14;
        while ((mantissa & 1024u) == 0u) {
            mantissa <<= 1;
            normalizedExponent--;
        }
        return uintBitsToFloat(signBit | (uint(normalizedExponent + 127) << 23) | ((mantissa & 1023u) << 13));
    }
    if (exponent == 31u) return uintBitsToFloat(signBit | 0x7f800000u | (mantissa << 13));
    return uintBitsToFloat(signBit | ((exponent + 112u) << 23) | (mantissa << 13));
}

float lfx_particle_float(uint binding, int component, uint particle, float fallback) {
    if (binding == 0xffffffffu) return fallback;
    uint index = ((binding & 0x7fffffffu) + uint(component)) * Attribute2.w + particle;
    if ((binding & 0x80000000u) == 0u) return texelFetch(NiagaraFloatData, int(index)).r;
    uint packedWord = texelFetch(NiagaraHalfData, int(index >> 1)).r;
    return lfx_half_to_float((packedWord >> ((index & 1u) * 16u)) & 0xffffu);
}

vec3 lfx_rotate(vec3 position, vec4 rotation) {
    vec3 twiceCross = 2.0 * cross(rotation.xyz, position);
    return position + rotation.w * twiceCross + cross(rotation.xyz, twiceCross);
}

#if LFX_EXTRA_MATERIAL
vec4 lfx_extra_material(uint binding, uint particle, vec4 fallback) {
    return vec4(lfx_particle_float(binding, 0, particle, fallback.x), lfx_particle_float(binding, 1, particle, fallback.y),
                lfx_particle_float(binding, 2, particle, fallback.z), lfx_particle_float(binding, 3, particle, fallback.w));
}
#endif


#endif
