#version 330

layout(std140) uniform GaussianSplatPostBlend {
    vec4 BlendParameters;   // (strength, lutEdge, preExposure, 0)
    vec4 ScratchUvTransform; // (uScale, vScale, uBias, vBias)
};

uniform sampler2D SplatColor;
#if LFX_APPLY_INVERSE_TONECURVE
uniform sampler3D InverseToneCurveLUT;
#endif

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 uv = texCoord * ScratchUvTransform.xy + ScratchUvTransform.zw;
    vec4 scratch = textureLod(SplatColor, uv, 0.0);
    vec3 color = max(scratch.rgb, vec3(0.0));
#if LFX_APPLY_INVERSE_TONECURVE
    float edge = BlendParameters.y;
    vec3 uvw = clamp(color, 0.0, 1.0) * ((edge - 1.0) / edge) + (0.5 / edge);
    vec3 corrected = textureLod(InverseToneCurveLUT, uvw, 0.0).rgb;
    color = mix(color, corrected, BlendParameters.x);
#endif
    fragColor = vec4(color * BlendParameters.z, clamp(scratch.a, 0.0, 1.0));
}
