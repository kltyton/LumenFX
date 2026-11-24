#version 330

layout(std140) uniform GaussianSplatDownsample {
    ivec4 DownsampleParams; // (factor, sourceMaxX, sourceMaxY, 0)
};

uniform sampler2D SceneDepthTexture;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    int factor = max(1, DownsampleParams.x);
    ivec2 sourceMax = DownsampleParams.yz;
    ivec2 basePixel = ivec2(gl_FragCoord.xy) * factor;
    float furthest = 1.0;
    for (int dy = 0; dy < factor; ++dy) {
        int y = min(basePixel.y + dy, sourceMax.y);
        for (int dx = 0; dx < factor; ++dx) {
            int x = min(basePixel.x + dx, sourceMax.x);
            furthest = min(furthest, texelFetch(SceneDepthTexture, ivec2(x, y), 0).r);
        }
    }
    gl_FragDepth = furthest;
    fragColor = vec4(0.0);
}
