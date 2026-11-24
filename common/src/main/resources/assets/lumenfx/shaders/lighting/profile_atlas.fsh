#version 330 core

uniform sampler2D ProfileSource;
in vec2 texCoord;
out float ProfileIntensity;

void main() {
    float intensity = textureLod(ProfileSource, texCoord, 0.0).r;
    ProfileIntensity = isnan(intensity) ? 0.0 : max(intensity, 0.0);
}
