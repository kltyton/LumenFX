#version 330

uniform sampler2D SourceDepth;
layout(std140) uniform DepthCopyInfo {
    ivec4 Dimensions;
};
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 srcSize = max(Dimensions.xy, ivec2(1));
    ivec2 dstSize = max(Dimensions.zw, ivec2(1));
    ivec2 samplePixel = srcSize == dstSize
            ? clamp(pixel, ivec2(0), srcSize - 1)
            : clamp(ivec2((vec2(pixel) + 0.5) * vec2(srcSize) / vec2(dstSize)), ivec2(0), srcSize - 1);
    gl_FragDepth = texelFetch(SourceDepth, samplePixel, 0).r;
    fragColor = vec4(0.0);
}
