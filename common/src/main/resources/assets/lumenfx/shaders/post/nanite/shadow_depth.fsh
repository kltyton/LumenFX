#version 330

uniform sampler2D NaniteVisibleDepth;
layout(std140) uniform NaniteShadowProjection {
    vec4 ShadowProjection;
    ivec4 ShadowViewport;
};
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy) - ShadowViewport.xy;
    float deviceDepth = texelFetch(NaniteVisibleDepth, pixel, 0).r;
    if (deviceDepth == 0.0) discard;
    if (ShadowViewport.z == 1) {
        deviceDepth = clamp(deviceDepth - ShadowProjection.y, 0.0, 1.0);
    } else if (ShadowViewport.z == 2) {
        float viewToClip22 = ShadowProjection.x;
        float linearDepth = viewToClip22 * (deviceDepth - 1.0) / (deviceDepth - viewToClip22);
        deviceDepth = clamp(1.0 - linearDepth - ShadowProjection.y, 0.0, 1.0);
    }
    gl_FragDepth = deviceDepth;
    fragColor = vec4(0.0);
}
