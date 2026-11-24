vec3 lfx_fog_light_sample_offset(ivec3 cell, int sampleIndex) {
    uvec4 value = uvec4(cell, FogParticleLightCount.w + 8 * sampleIndex);
    value = value * 1664525u + 1013904223u;
    value.x += value.y * value.w;
    value.y += value.z * value.x;
    value.z += value.x * value.y;
    value.w += value.y * value.z;
    value ^= value >> 16u;
    value.x += value.y * value.w;
    value.y += value.z * value.x;
    value.z += value.x * value.y;
    value.w += value.y * value.z;
    vec3 random = (vec3(value.xyz) / float(0xffffffffu)) * 2.0 - 1.0;
    return FogLightJitterOffsets[sampleIndex].xyz + FogParticleLightExposure.y * random;
}
