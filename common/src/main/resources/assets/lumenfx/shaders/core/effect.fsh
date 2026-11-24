#version 330
#moj_import <lumenfx:material_fog.glsl>
uniform sampler2D Sampler0;
layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; };
in vec2 effectUV;
in vec2 effectUV1;
in vec4 effectColor;
in vec4 effectParticle;
in vec4 effectCustom;
out vec4 fragColor;
void main() {
    fragColor = lfx_apply_material_fog(texture(Sampler0, effectUV) * effectColor);
}
