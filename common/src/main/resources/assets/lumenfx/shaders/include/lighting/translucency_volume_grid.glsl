#ifndef LFX_TRANSLUCENCY_VOLUME_GRID
#define LFX_TRANSLUCENCY_VOLUME_GRID

const vec3 LFX_TLV_LUMA = vec3(0.2126390059, 0.7151686788, 0.0721923154);

layout(std140) uniform TranslucencyLightingVolume {
    vec4 TlvCascade[2];
    ivec4 TlvAtlas;
    vec4 TlvExposure;
    vec4 TlvNoise;
};

ivec3 lfx_tlv_voxel(ivec2 pixel) {
    ivec2 tile = pixel / TlvAtlas.x;
    return ivec3(pixel - tile * TlvAtlas.x, tile.x + tile.y * TlvAtlas.y);
}

ivec2 lfx_tlv_pixel(ivec3 voxel) {
    return ivec2(voxel.z % TlvAtlas.y, voxel.z / TlvAtlas.y) * TlvAtlas.x + voxel.xy;
}

float lfx_tlv_border(vec3 voxelCentre) {
    float dimension = float(TlvAtlas.x);
    vec3 fadeUV = (voxelCentre / dimension) * (1.0 + 4.0 / dimension) - 2.0 / dimension;
    vec3 fade = clamp((0.5 - abs(fadeUV - 0.5)) * 10.0, 0.0, 1.0);
    return fade.x * fade.y * fade.z;
}

vec4 lfx_tlv_slice(sampler2D atlas, vec2 centre, int slice) {
    int dimension = TlvAtlas.x;
    ivec2 tile = ivec2(slice % TlvAtlas.y, slice / TlvAtlas.y);
    vec2 pixel = vec2(tile * dimension) + clamp(centre, vec2(0.5), vec2(float(dimension) - 0.5));
    return textureLod(atlas, pixel / vec2(TlvAtlas.yz * dimension), 0.0);
}

vec4 lfx_tlv_sample(sampler2D atlas, vec3 uv) {
    float z = clamp(uv.z * float(TlvAtlas.x) - 0.5, 0.0, float(TlvAtlas.x - 1));
    int low = int(z);
    vec2 centre = uv.xy * float(TlvAtlas.x);
    return mix(lfx_tlv_slice(atlas, centre, low),
        lfx_tlv_slice(atlas, centre, min(low + 1, TlvAtlas.x - 1)), fract(z));
}

#endif
