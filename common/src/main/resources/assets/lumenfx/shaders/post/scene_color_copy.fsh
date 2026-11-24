#version 330
#moj_import <lumenfx:srgb.glsl>
uniform sampler2D SceneSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
#if LFX_SCENE_COLOR_SOURCE_SRGB
    scene.rgb = srgbToLinear(scene.rgb);
#endif
    fragColor = vec4(pow(scene.rgb * 0.1, vec3(0.25)), scene.a);
}
