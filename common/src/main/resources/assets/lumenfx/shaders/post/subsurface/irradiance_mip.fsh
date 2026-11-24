#version 330
#moj_import <lumenfx:lighting/burley_mip_settings.glsl>
uniform sampler2D ParentIrradiance;
in vec2 texCoord;
out vec4 mipValue;

void main() {
    if (!lfx_burley_use_mips()) discard;
    mipValue = textureLod(ParentIrradiance, texCoord, 0.0);
}
