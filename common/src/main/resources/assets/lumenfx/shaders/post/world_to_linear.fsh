#version 330
#moj_import <lumenfx:srgb.glsl>
uniform sampler2D WorldSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 world = texture(WorldSampler, texCoord);
    vec3 encoded = max(world.rgb, vec3(0.0));
#if LFX_WORLD_ENCODING == 2
    vec3 linear = pow(encoded, vec3(2.2));
#elif LFX_WORLD_ENCODING == 0
    vec3 linear = encoded;
#else
    vec3 linear = srgbToLinear(encoded);
#endif
    fragColor = vec4(linear, world.a);
}
