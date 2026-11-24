#version 330

uniform samplerBuffer NaniteHardwarePositions;
uniform usamplerBuffer NaniteHardwarePrimitives;

layout(std140) uniform NaniteHardwareOffsets {
    ivec4 HardwareOffsets;
};

flat out uvec2 primitiveData;
noperspective out float lfxNaniteUnclippedDepth;
#if LFX_NANITE_RASTER_MATERIAL
uniform usamplerBuffer NaniteShadingPrimitives;
uniform samplerBuffer NaniteVertexInterpolants;
layout(std140) uniform NaniteInterpolantLayout { ivec4 NaniteInterpolantInfo; };
#moj_import <lumenfx:nanite/instance_data.glsl>
out vec4 lfxNaniteCoverageUV01;
#endif

void main() {
    int triangle = gl_VertexID / 3;
    primitiveData = texelFetch(NaniteHardwarePrimitives, HardwareOffsets.y + triangle).xy;
#if LFX_NANITE_RASTER_MATERIAL
    uvec4 primitive = texelFetch(NaniteShadingPrimitives, NaniteShadingOffsets.y + triangle);
    int corner = gl_VertexID % 3;
    bool reverse = ((primitive.w ^ (primitive.w >> 1u)) & 1u) != 0u;
    if (reverse && corner != 0) corner = 3 - corner;
    int first = NaniteInterpolantInfo.x + (triangle * 3 + corner) * NaniteInterpolantInfo.y;
    lfxNaniteCoverageUV01 = texelFetch(NaniteVertexInterpolants, first + 1);
#endif
    gl_Position = texelFetch(NaniteHardwarePositions, HardwareOffsets.x + gl_VertexID);
    if ((primitiveData.y & 8u) != 0u) {
        float ndc = gl_Position.z / gl_Position.w;
        lfxNaniteUnclippedDepth = (primitiveData.y & 16u) != 0u ? ndc : ndc * 0.5 + 0.5;
        gl_Position.z = 0.5 * gl_Position.w;
    } else lfxNaniteUnclippedDepth = 0.0;
#if LFX_NANITE_TEXTURE_Y_DOWN
    gl_Position.y = -gl_Position.y;
#endif
}
