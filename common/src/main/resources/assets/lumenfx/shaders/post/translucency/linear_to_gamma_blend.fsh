#version 330
#moj_import <lumenfx:srgb.glsl>

uniform sampler2D ParticleSceneSampler;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out uint fragCoverage;

void main() {
    vec4 scene = texelFetch(ParticleSceneSampler, ivec2(gl_FragCoord.xy), 0);
    fragColor = vec4(sign(scene.rgb) * linearToSrgb(abs(scene.rgb)), scene.a);
    fragCoverage = 0u;
}
