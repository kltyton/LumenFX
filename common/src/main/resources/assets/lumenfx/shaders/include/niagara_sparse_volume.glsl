#ifndef LFX_NIAGARA_SPARSE_VOLUME
#define LFX_NIAGARA_SPARSE_VOLUME

float lfx_sparse_address(float value, int mode) {
    if (mode == 0) return fract(value);
    if (mode == 2) return 1.0 - abs(2.0 * fract(0.5 * value) - 1.0);
    return clamp(value, 0.0, 1.0);
}

float lfx_sparse_mip(uvec4 pages, vec3 dx, vec3 dy, float bias) {
    vec3 size = uintBitsToFloat(pages.xyz) * 16.0;
    dx *= size;
    dy *= size;
    return max(0.0, 0.5 * log2(max(max(dot(dx, dx), dot(dy, dy)), 1.0e-8)) + bias);
}

vec4 lfx_sparse_sample_mip(usampler3D pageTable, sampler3D atlas, uvec4 pages, uvec4 storage, vec3 uvw, int mip) {
    uint packedOffset = pages.w;
    vec3 offset = vec3(packedOffset & 2047u, (packedOffset >> 11u) & 2047u, packedOffset >> 22u);
    float scale = 1.0 / float(1u << uint(mip));
    vec3 position = uvw * uintBitsToFloat(pages.xyz) * scale - floor(offset * scale);
    ivec3 page = ivec3(floor(position));
    uint tile = 0u;
    if (all(greaterThanEqual(page, ivec3(0))) && all(lessThan(page, textureSize(pageTable, mip))))
        tile = texelFetch(pageTable, page, mip).r;
    uvec3 physical = uvec3(tile & 255u, (tile >> 8u) & 255u, (tile >> 16u) & 255u);
    uint residentMip = tile >> 24u;
    float residentScale = 1.0 / float(1u << ((residentMip - uint(mip)) & 31u));
    vec3 voxel = vec3(physical) * 18.0 + fract(position * residentScale) * 16.0 + 1.0;
    return textureLod(atlas, voxel * uintBitsToFloat(storage.xyz), 0.0);
}

vec4 lfx_sample_sparse(usampler3D pageTable, sampler3D atlas, uvec4 pages, uvec4 storage,
                       vec3 uvw, ivec3 address, float mip) {
    uvw = vec3(lfx_sparse_address(uvw.x, address.x), lfx_sparse_address(uvw.y, address.y),
               lfx_sparse_address(uvw.z, address.z));
    float maxMip = float(storage.w >> 16u);
    float level = clamp(mip, 0.0, maxMip);
    int low = int(floor(level)), high = int(ceil(level));
    vec4 first = lfx_sparse_sample_mip(pageTable, atlas, pages, storage, uvw, low);
    if (low == high) return first;
    return mix(first, lfx_sparse_sample_mip(pageTable, atlas, pages, storage, uvw, high), fract(level));
}

#endif
