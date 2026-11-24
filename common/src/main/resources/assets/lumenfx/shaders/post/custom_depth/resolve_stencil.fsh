#version 330
uniform sampler2D StencilLow;
uniform sampler2D StencilHigh;
layout(location=0) out uvec2 fragStencil;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 low = texelFetch(StencilLow, pixel, 0);
    vec4 high = texelFetch(StencilHigh, pixel, 0);
    float stencil = dot(low, vec4(1.0, 2.0, 4.0, 8.0)) + dot(high, vec4(16.0, 32.0, 64.0, 128.0));
    fragStencil = uvec2(0u, uint(stencil));
}
