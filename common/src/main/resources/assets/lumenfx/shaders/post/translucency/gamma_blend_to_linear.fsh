#version 330
#moj_import <lumenfx:srgb.glsl>

uniform sampler2D ParticleSceneSampler;
uniform usampler2D ParticleCoverageSampler;
out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (texelFetch(ParticleCoverageSampler, pixel, 0).r == 0u) discard;
    vec4 scene = texelFetch(ParticleSceneSampler, pixel, 0);
    fragColor = vec4(sign(scene.rgb) * srgbToLinear(abs(scene.rgb)), scene.a);
}
