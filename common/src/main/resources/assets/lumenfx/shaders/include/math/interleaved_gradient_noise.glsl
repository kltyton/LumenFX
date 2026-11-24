float lfx_interleaved_gradient_noise(vec2 pixel, float frame) {
    pixel += frame * (vec2(47.0, 17.0) * 0.695);
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}
