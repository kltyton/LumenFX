#ifndef LFX_NANITE_INSTANCE_DATA
#define LFX_NANITE_INSTANCE_DATA

#if LFX_NANITE_VERTEX_MATERIAL || LFX_NANITE_SOFTWARE_RASTER
layout(std430) readonly buffer NaniteShadingInstanceFloats { float lfxNaniteInstanceFloats[]; };
layout(std430) readonly buffer NaniteShadingTransforms { vec4 lfxNaniteTransforms[]; };
#else
uniform samplerBuffer NaniteShadingInstanceFloats;
uniform samplerBuffer NaniteShadingTransforms;
#endif

layout(std140) uniform NaniteShadingView {
    mat4 NaniteCurrentClip;
    mat4 NanitePreviousClip;
    ivec4 NaniteShadingOffsets;
    vec4 NaniteShadingViewport;
    ivec4 NaniteShadingInstanceLayout;
    ivec4 NaniteShadingPixelOffset;
};

bool lfx_nanite_fixed_raster() {
    return (NaniteShadingInstanceLayout.w & 128) != 0;
}

bool lfx_nanite_shadow_depth() {
    return (NaniteShadingInstanceLayout.w & 256) != 0;
}

float lfx_nanite_instance_float(uint instanceId, int component, float fallback) {
    int stride = NaniteShadingInstanceLayout.y;
    if (component < 0 || component >= stride) return fallback;
    int index = int(instanceId) * stride + component;
#if LFX_NANITE_VERTEX_MATERIAL || LFX_NANITE_SOFTWARE_RASTER
    return lfxNaniteInstanceFloats[index];
#else
    index += NaniteShadingInstanceLayout.x;
    return texelFetch(NaniteShadingInstanceFloats, index).r;
#endif
}

vec4 lfx_nanite_transform_word(uint instanceId, int word) {
    int index = int(instanceId) * 9 + word;
#if LFX_NANITE_VERTEX_MATERIAL || LFX_NANITE_SOFTWARE_RASTER
    return lfxNaniteTransforms[index];
#else
    return texelFetch(NaniteShadingTransforms, NaniteShadingInstanceLayout.z + index);
#endif
}

void lfx_nanite_instance_transform(uint instanceId, out mat3 linear, out vec3 center, out vec3 inverseScale) {
    linear = mat3(lfx_nanite_transform_word(instanceId, 0).xyz,
                  lfx_nanite_transform_word(instanceId, 1).xyz,
                  lfx_nanite_transform_word(instanceId, 2).xyz);
    center = lfx_nanite_transform_word(instanceId, 3).xyz;
    inverseScale = lfx_nanite_transform_word(instanceId, 4).xyz;
}

#endif
