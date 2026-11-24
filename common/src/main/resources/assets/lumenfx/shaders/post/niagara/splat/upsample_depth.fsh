#version 330

layout(std140) uniform GaussianSplatUpsample {
    vec4 UpsampleScale; // (downsampledW / outputW, downsampledH / outputH, maxDownsampledX, maxDownsampledY)
};

uniform sampler2D SplatDepthTexture;
uniform sampler2D DownsampledSceneDepthTexture;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy * UpsampleScale.xy), ivec2(0), ivec2(UpsampleScale.zw));
    float splatDepth = texelFetch(SplatDepthTexture, pixel, 0).r;
    float sceneDepth = texelFetch(DownsampledSceneDepthTexture, pixel, 0).r;
    if (splatDepth <= sceneDepth) discard;
    gl_FragDepth = splatDepth;
    fragColor = vec4(0.0);
}
