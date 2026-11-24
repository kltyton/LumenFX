#version 330
#moj_import <lumenfx:srgb.glsl>
uniform sampler2D WorldSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 scene = texture(WorldSampler, texCoord);
#if LFX_WORLD_ENCODING == 2
    fragColor = vec4(pow(max(scene.rgb, vec3(0.0)), vec3(1.0 / 2.2)), scene.a);
#elif LFX_WORLD_ENCODING == 1
    fragColor = vec4(linearToSrgb(scene.rgb), scene.a);
#else
    fragColor = scene;
#endif
}
