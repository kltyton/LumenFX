#version 330

uniform sampler2D StochasticDepthTexture;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float deviceDepth = texelFetch(StochasticDepthTexture, ivec2(gl_FragCoord.xy), 0).r;
    if (deviceDepth <= 0.0) discard;
    gl_FragDepth = deviceDepth;
    fragColor = vec4(0.0);
}
