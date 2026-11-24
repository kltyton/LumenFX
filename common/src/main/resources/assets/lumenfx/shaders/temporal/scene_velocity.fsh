#version 330
#moj_import <lumenfx:scene_velocity.glsl>

layout(std140) uniform SceneMotion {
    vec4 MotionViewport;
    vec4 MotionJitter;
};

uniform sampler2D SceneDepthSampler;
uniform sampler2D PreviousNdcSampler;
uniform sampler2D CoverageDepthSampler;
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(SceneDepthSampler, pixel, 0).r;
    vec4 previous = texelFetch(PreviousNdcSampler, pixel, 0);
    vec2 coverage = texelFetch(CoverageDepthSampler, pixel, 0).rg;
    if (MotionViewport.w < 0.5 || previous.w < 1.0 || depth != coverage.y) {
        fragColor = vec4(0.0);
        return;
    }
    vec2 currentNdc = gl_FragCoord.xy / MotionViewport.xy * 2.0 - 1.0;
    float previousDepth = MotionViewport.z > 0.5 ? previous.z : previous.z * 0.5 + 0.5;
    float currentDepth = MotionViewport.z > 0.5 ? coverage.x : coverage.x * 0.5 + 0.5;
    vec2 velocity = (currentNdc - MotionJitter.xy) - (previous.xy - MotionJitter.zw);
    velocity.y = -velocity.y;
    fragColor = lfx_encode_scene_velocity(vec3(velocity, currentDepth - previousDepth), 0u);
}
