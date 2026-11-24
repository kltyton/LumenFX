#version 330

layout(std140) uniform GaussianSplatExpectedDepth {
    vec4 ViewToClip22_23_32_33;
    vec4 DepthScaleFlags; // (expectedDepthScale, zZeroToOne, 0, 0)
};

uniform sampler2D DepthMomentsTexture;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 moments = texelFetch(DepthMomentsTexture, ivec2(gl_FragCoord.xy), 0);
    float denominator = 1.0 - moments.a;
    if (denominator <= 1e-5) discard;
    float linearZ = (moments.r / denominator) * DepthScaleFlags.x;
    float clipZ = linearZ * ViewToClip22_23_32_33.x + ViewToClip22_23_32_33.z;
    float clipW = linearZ * ViewToClip22_23_32_33.y + ViewToClip22_23_32_33.w;
    if (clipW <= 0.0) discard;
    float ndcZ = clipZ / clipW;
    float deviceZ = DepthScaleFlags.y > 0.5 ? ndcZ : ndcZ * 0.5 + 0.5;
    if (deviceZ <= 0.0) discard;
    gl_FragDepth = clamp(deviceZ, 0.0, 1.0);
    fragColor = vec4(0.0);
}
