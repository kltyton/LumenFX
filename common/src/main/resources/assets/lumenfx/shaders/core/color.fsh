#version 330
#moj_import <lumenfx:material_fog.glsl>
layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; };
in vec4 effectColor;
out vec4 fragColor;
void main() {
    fragColor = lfx_apply_material_fog(effectColor);
}
