#version 330
uniform sampler2D SurfaceNormal;
layout(std140) uniform NormalHistoryView { mat4 ViewToWorld; };
out vec4 fragColor;

void main() {
    vec3 viewNormal = texelFetch(SurfaceNormal, ivec2(gl_FragCoord.xy), 0).xyz;
    vec3 world = (mat3(ViewToWorld) * viewNormal).xzy;
    bool valid = dot(world, world) > 0.0;
    fragColor = valid ? vec4(normalize(world) * 0.5 + 0.5, 1.0) : vec4(0.5, 0.5, 0.5, 0.0);
}
