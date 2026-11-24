#ifndef LFX_VOLUMETRIC_LIGHTMAP
#define LFX_VOLUMETRIC_LIGHTMAP
layout(std140) uniform LfxVolumetricLightmap {
    vec4 LfxVlmScaleAndBrick;
    vec4 LfxVlmOffsetAndScattering;
};
uniform sampler2DArray LfxVlmIndirection;
uniform sampler2DArray LfxVlmAmbient;
uniform sampler2DArray LfxVlmShRed;
uniform sampler2DArray LfxVlmShGreen;
uniform sampler2DArray LfxVlmShBlue;
uniform sampler2DArray LfxVlmSkyBent;

bool lfx_vlm_coordinate(vec3 translatedSourcePosition, out vec3 brick) {
    vec3 uv = clamp(translatedSourcePosition * LfxVlmScaleAndBrick.xyz + LfxVlmOffsetAndScattering.xyz, 0.0, 0.99);
    vec3 texel = uv * vec3(textureSize(LfxVlmIndirection, 0));
    vec4 address = round(texelFetch(LfxVlmIndirection, ivec3(texel), 0) * 255.0);
    if (address.w <= 0.0) return false;
    float brickSize = LfxVlmScaleAndBrick.w;
    brick = address.xyz * (brickSize + 1.0) + fract(texel / address.w) * brickSize;
    return true;
}

vec4 lfx_vlm_sample(sampler2DArray image, vec3 brick) {
    ivec3 size = textureSize(image, 0);
    vec2 uv = (brick.xy + 0.5) / vec2(size.xy);
    float slice = clamp(brick.z, 0.0, float(size.z - 1));
    float lower = floor(slice);
    return mix(textureLod(image, vec3(uv, lower), 0.0),
               textureLod(image, vec3(uv, min(lower + 1.0, float(size.z - 1))), 0.0), fract(slice));
}
#endif
