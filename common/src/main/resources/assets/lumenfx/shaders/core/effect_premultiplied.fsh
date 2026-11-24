#version 330
#moj_import <lumenfx:material_fog.glsl>
uniform sampler2D Sampler0;
layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; };
in vec2 effectUV;
in vec4 effectColor;
out vec4 fragColor;
void main() {
    vec4 color = texture(Sampler0, effectUV) * effectColor;
    fragColor = lfx_apply_material_fog(vec4(color.rgb * color.a, color.a));
}
