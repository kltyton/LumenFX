uniform sampler2D LfxMaterialScalarBlueNoise;

float lfx_material_scalar_blue_noise(vec2 pixel) {
    uvec3 address = uvec3(uvec2(max(pixel, vec2(0.0))), uint(MaterialCamera.w)) & uvec3(127u, 127u, 63u);
    return texelFetch(LfxMaterialScalarBlueNoise, ivec2(address.x, address.y + address.z * 128u), 0).r;
}
