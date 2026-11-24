#moj_import <lumenfx:lighting/clear_coat_normals.glsl>
#moj_import <lumenfx:substrate/slab_encoding.glsl>

layout(std140) uniform LfxSubstratePacking { ivec4 LfxSubstratePackingView; };

vec3 lfx_substrate_top_normal(vec3 normal) {
    vec2 oct = lfx_clear_coat_octahedron(normalize(normal));
    if ((LfxSubstratePackingView.w & 2) != 0)
        oct = vec2(lfx_substrate_round_to_half(oct.x), lfx_substrate_round_to_half(oct.y));
    else
        oct = (vec2(ivec2(clamp(oct * 1023.0 + 1024.0, 0.0, 2047.0))) - 1024.0) / 1023.0;
    return lfx_clear_coat_unit_vector(oct);
}

vec3 lfx_substrate_color8(vec3 color) {
    bool precise = LfxSubstratePackingView.y >= 1;
    color = clamp(color, 0.0, 1.0);
    vec3 encoded = precise ? linearToSrgb(color) : lfx_substrate_sqrt_fast(color);
    encoded = vec3(uvec3(clamp(encoded, 0.0, 1.0) * 255.0)) / 255.0;
    return precise ? srgbToLinear(encoded) : encoded * encoded;
}
