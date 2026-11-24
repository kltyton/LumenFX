uniform sampler2D SceneDepthSampler;

float lfx_material_scene_window_depth(vec2 uv) {
    ivec2 size = textureSize(SceneDepthSampler, 0);
    ivec2 coord = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
    return texelFetch(SceneDepthSampler, coord, 0).r;
}

vec3 lfx_material_cell_world(ivec3 cell, vec3 offset, out float slice) {
    vec2 gridXY = vec2(cell.xy) + offset.xy;
    slice = float(cell.z) + offset.z;
    vec3 position = lfx_cell_world_at_slice(gridXY, slice);
    if (FogFrame.z >= 0.0) {
        vec4 clip = FogWorldToClip * vec4(position, 1.0);
        float sceneDepth = lfx_window_to_depth_cm(lfx_material_scene_window_depth(lfx_scene_uv(clip)));
        float coordinate = lfx_slice_from_depth(sceneDepth) - FogFrame.z;
        float delta = coordinate - (float(cell.z) + offset.z);
        if (delta < 0.0 && -delta < FogFrame.w) {
            offset.z += delta;
            slice = float(cell.z) + offset.z;
            position = lfx_cell_world_at_slice(gridXY, slice);
        }
    }
    return position;
}

vec3 lfx_material_cell_world(ivec3 cell, out float slice) {
    return lfx_material_cell_world(cell, vec3(0.5), slice);
}
