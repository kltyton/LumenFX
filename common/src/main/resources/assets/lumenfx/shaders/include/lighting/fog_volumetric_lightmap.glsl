#moj_import <lumenfx:lighting/volumetric_lightmap.glsl>

vec3 lfx_fog_vlm_scattering(vec3 brick, vec3 cameraDirection, float g) {
    vec3 ambient = lfx_vlm_sample(LfxVlmAmbient, brick).rgb;
    vec3 red = lfx_vlm_sample(LfxVlmShRed, brick).rgb * 2.0 - 1.0;
    vec3 green = lfx_vlm_sample(LfxVlmShGreen, brick).rgb * 2.0 - 1.0;
    vec3 blue = lfx_vlm_sample(LfxVlmShBlue, brick).rgb * 2.0 - 1.0;
    vec3 hg = cameraDirection.yzx * (g * (0.488603 / 0.282095));
    vec3 irradiance = ambient * (vec3(1.0) + vec3(dot(red, hg), dot(green, hg), dot(blue, hg)));
    return max(irradiance, vec3(0.0)) * LfxVlmOffsetAndScattering.w;
}
