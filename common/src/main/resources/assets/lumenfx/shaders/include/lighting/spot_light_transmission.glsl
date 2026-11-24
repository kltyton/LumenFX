const vec2 lfxSpotTransmissionOffsets[16] = vec2[](
    vec2(0.4036454, -0.793536), vec2(0.3826454, -0.2730118), vec2(-0.04124885, -0.5971786),
    vec2(-0.3709261, -0.9179904), vec2(-0.3795351, -0.3353493), vec2(-0.3154466, 0.1069074),
    vec2(-0.7671808, -0.6143452), vec2(-0.4865215, 0.6395131), vec2(0.2359872, 0.1510548),
    vec2(0.03092861, 0.7309022), vec2(-0.82846, -0.1055831), vec2(0.9268684, 0.3764873),
    vec2(0.8732378, -0.116035), vec2(0.8239923, -0.515173), vec2(0.5084407, 0.7533528),
    vec2(-0.975402, -0.0711386));

float lfx_spot_subsurface_optical_mask(sampler2D depth, mat4 projection, vec3 toLight, vec3 normal,
                                       float extinctionPerCm, float normalScale, vec2 perspective,
                                       float inverseResolution, float depthRange, float bias) {
    normalScale *= 0.5;
    vec4 clip = projection * vec4(-toLight - normal * normalScale, 1.0);
    if (clip.w <= 0.0) return 1.0;
    vec2 uv = lfx_spot_shadow_uv(clip);
    float receiver = lfx_spot_shadow_depth(clip.w, perspective) + bias;
    float optical = 0.0;
    for (int index = 0; index < 16; ++index) {
        vec2 sampleUv = uv + lfxSpotTransmissionOffsets[index] * vec2(1.0, -1.0) * inverseResolution;
        float caster = lfx_spot_shadow_sample(depth, sampleUv);
        float sampleDepth = (receiver - caster) * extinctionPerCm * depthRange;
        optical += clamp(abs(max(0.0, sampleDepth + normalScale)), 0.15, 5.0) + 0.25;
    }
    return clamp(1.0 - optical / 80.0, 0.0, 1.0);
}
