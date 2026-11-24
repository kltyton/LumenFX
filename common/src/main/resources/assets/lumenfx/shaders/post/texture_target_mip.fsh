#version 330
uniform sampler2D MipIn;
in vec2 texCoord;
out vec4 fragColor;

void main() { fragColor = textureLod(MipIn, texCoord, 0.0); }
