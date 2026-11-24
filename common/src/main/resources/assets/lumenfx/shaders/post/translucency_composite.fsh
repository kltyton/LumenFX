#version 330
uniform sampler2D SceneSampler;
uniform sampler2D TranslucencySampler;
uniform sampler2D ModulationSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 scene = texture(SceneSampler, texCoord);
    scene.rgb *= texture(ModulationSampler, texCoord).rgb;
    vec4 layer = texture(TranslucencySampler, texCoord);
    scene.rgb = layer.rgb + scene.rgb * layer.a;
    fragColor = scene;
}
