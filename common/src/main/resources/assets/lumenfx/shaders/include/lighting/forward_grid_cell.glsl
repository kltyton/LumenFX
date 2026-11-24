int lfx_forward_grid_cell(vec3 position, ivec4 shape, vec4 distribution, mat4 worldToView) {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (distribution.w > 0.0) pixel.y = int(distribution.w) - 1 - pixel.y;
    ivec2 tile = clamp(pixel >> shape.w, ivec2(0), shape.xy - 1);
    float depth = -(worldToView * vec4(position, 1.0)).z * 100.0;
    float slice = log2(max(1.0, depth * distribution.x + distribution.y)) * distribution.z;
    int z = int(clamp(slice, 0.0, float(shape.z - 1)));
    return (z * shape.y + tile.y) * shape.x + tile.x;
}
