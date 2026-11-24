vec4 lfx_stencil_bits(int value, ivec4 bits) {
    return vec4(notEqual(ivec4(value) & bits, ivec4(0)));
}
