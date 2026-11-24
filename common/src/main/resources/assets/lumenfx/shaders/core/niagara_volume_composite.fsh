#version 330

uniform sampler2D VolumeRadiance;
out vec4 fragColor;

void main() {
    fragColor = texelFetch(VolumeRadiance, ivec2(gl_FragCoord.xy), 0);
}
