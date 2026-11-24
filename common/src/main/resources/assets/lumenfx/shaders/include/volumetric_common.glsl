#moj_import <lumenfx:fog_volume_uniforms.glsl>

#if !defined(LFX_COMPUTE_INTEGRATION) && !defined(LFX_VERTEX_FOG) && !defined(LFX_VOLUME_COMPUTE)
bool lfx_atlas_cell(out ivec3 cell) {
    ivec2 frag = ivec2(gl_FragCoord.xy);
    int nx = int(FogGrid.x + 0.5);
    int ny = int(FogGrid.y + 0.5);
    int nz = int(FogGrid.z + 0.5);
    int cols = int(FogGrid.w + 0.5);
    int col = frag.x / nx;
    int localX = frag.x - col * nx;
    int row = frag.y / ny;
    int localY = frag.y - row * ny;
    int z = row * cols + col;
    cell = ivec3(localX, localY, z);
    return col >= 0 && col < cols && localX >= 0 && localY >= 0 && z >= 0 && z < nz;
}
#endif

vec2 lfx_atlas_uv(vec3 logical, vec4 grid, vec4 atlas) {
    float cols = grid.w;
    float nx = grid.x;
    float ny = grid.y;
    float tile = clamp(logical.z, 0.0, grid.z - 1.0);
    vec2 local = clamp(logical.xy, vec2(0.5), vec2(nx - 0.5, ny - 0.5));
    float tileCol = mod(tile, cols);
    float tileRow = floor(tile / cols);
    return (local + vec2(tileCol * nx, tileRow * ny)) * atlas.zw;
}

vec4 lfx_sample_atlas(sampler2D atlasSampler, vec3 logical, vec4 grid, vec4 atlas) {
    float z = clamp(logical.z, 0.5, grid.z - 0.5);
    float z0 = floor(z - 0.5);
    float blend = z - 0.5 - z0;
    float z1 = min(z0 + 1.0, grid.z - 1.0);
    vec4 lower = textureLod(atlasSampler, lfx_atlas_uv(vec3(logical.xy, z0), grid, atlas), 0.0);
    vec4 upper = textureLod(atlasSampler, lfx_atlas_uv(vec3(logical.xy, z1), grid, atlas), 0.0);
    return mix(lower, upper, blend);
}

float lfx_window_to_ndc_z(float windowZ) {
    return FogProjection.z > 0.5 ? windowZ : windowZ * 2.0 - 1.0;
}

float lfx_ndc_to_window_z(float ndcZ) {
    return FogProjection.z > 0.5 ? ndcZ : (ndcZ + 1.0) * 0.5;
}

float lfx_ndc_to_depth_cm(float ndcZ) {
    return FogProjection.y / (ndcZ + FogProjection.x);
}

float lfx_window_to_depth_cm(float windowZ) {
    return lfx_ndc_to_depth_cm(lfx_window_to_ndc_z(windowZ));
}

float lfx_depth_from_slice(float zSlice) {
    return (exp2(zSlice / FogDepth.z) - FogDepth.y) / FogDepth.x;
}

float lfx_slice_from_depth(float depthCm) {
    return log2(depthCm * FogDepth.x + FogDepth.y) * FogDepth.z;
}

vec3 lfx_reconstruct_world(vec2 ndcXY, float depthCm) {
    float ndcZ = FogProjection.y / depthCm - FogProjection.x;
    vec4 world = FogClipToWorld * vec4(ndcXY, ndcZ, 1.0);
    return world.xyz / world.w;
}

vec3 lfx_cell_world_at_slice(vec2 gridXY, float zSlice) {
    vec2 uv = gridXY / FogGrid.xy;
    vec2 ndc = (uv * 2.0 - 1.0) * vec2(1.0, -1.0);
    return lfx_reconstruct_world(ndc, lfx_depth_from_slice(max(zSlice, 0.0)));
}

vec2 lfx_scene_uv(vec4 clipPosition) {
    return clipPosition.xy / clipPosition.w * 0.5 + 0.5;
}

vec2 lfx_volume_uv(vec4 clipPosition) {
    return clipPosition.xy / clipPosition.w * vec2(0.5, -0.5) + 0.5;
}

float lfx_positive_finite(float value) {
    return floatBitsToUint(value) < 0x7f800000u ? value : 0.0;
}

vec4 lfx_positive_finite_vec(vec4 value) {
    return vec4(lfx_positive_finite(value.x), lfx_positive_finite(value.y),
                lfx_positive_finite(value.z), lfx_positive_finite(value.w));
}
