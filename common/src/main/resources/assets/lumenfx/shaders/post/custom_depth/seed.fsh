#version 330
#moj_import <lumenfx:depth/stencil_bits.glsl>
#ifdef LFX_HOST_CUSTOM_DEPTH
uniform sampler2D HostCustomDepth;
#endif
#ifdef LFX_HOST_CUSTOM_STENCIL
uniform usampler2D HostCustomStencil;
#endif
#ifdef LFX_WRITE_CUSTOM_STENCIL
layout(location=1) out vec4 StencilLow;
layout(location=2) out vec4 StencilHigh;
#endif
layout(location=0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
#ifdef LFX_HOST_CUSTOM_DEPTH
    gl_FragDepth = texelFetch(HostCustomDepth, pixel, 0).r;
#else
    gl_FragDepth = 0.0;
#endif
#ifdef LFX_WRITE_CUSTOM_STENCIL
    int stencil = 0;
#ifdef LFX_HOST_CUSTOM_STENCIL
    stencil = int(texelFetch(HostCustomStencil, pixel, 0).g);
#endif
    StencilLow = lfx_stencil_bits(stencil, ivec4(1, 2, 4, 8));
    StencilHigh = lfx_stencil_bits(stencil, ivec4(16, 32, 64, 128));
#endif
    fragColor = vec4(0.0);
}
