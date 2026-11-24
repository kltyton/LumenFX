#moj_import <lumenfx:lighting/indirect_occlusion.glsl>

layout(std140) uniform LfxForwardIndirectDiffuse {
    vec4 ForwardSkyIrradiance[7];
    vec4 ForwardSkyColor;
    vec4 ForwardPrecomputedScale;
    vec4 LfxIndirectLuminanceAndHeight;
};

float lfx_precomputed_irradiance_channel(vec3 irradiance, float ao) {
    vec2 pixel = gl_FragCoord.xy;
    if (LfxIndirectLuminanceAndHeight.w > 0.0) pixel.y = LfxIndirectLuminanceAndHeight.w - pixel.y;
    vec2 p = fract(pixel / 128.0) * 128.0 + vec2(-64.340622, -72.465622);
    float dither = fract(dot(p.xyx * p.xyy, vec3(20.390625, 60.703125, 2.4281209))) - 0.5;
    float brightness = dot(irradiance, LfxIndirectLuminanceAndHeight.xyz) * ao;
    float encoded = log2(brightness + 0.00390625) * (1.0 / 16.0) + 0.5;
    return clamp(encoded * 255.0 + dither, 0.0, 255.0);
}

vec3 lfx_forward_sky_diffuse(vec3 normal) {
    vec4 basis1 = vec4(normal, 1.0);
    vec4 basis2 = normal.xyzz * normal.yzzx;
    vec3 result = vec3(dot(ForwardSkyIrradiance[0], basis1), dot(ForwardSkyIrradiance[1], basis1),
                       dot(ForwardSkyIrradiance[2], basis1));
    result += vec3(dot(ForwardSkyIrradiance[3], basis2), dot(ForwardSkyIrradiance[4], basis2),
                   dot(ForwardSkyIrradiance[5], basis2));
    result += ForwardSkyIrradiance[6].rgb * (normal.x * normal.x - normal.y * normal.y);
    return max(vec3(0.0), result) * ForwardSkyColor.rgb;
}

#if LFX_FORWARD_VOLUMETRIC_LIGHTMAP || LFX_OPAQUE_VOLUMETRIC_LIGHTMAP
#moj_import <lumenfx:lighting/volumetric_lightmap.glsl>
uniform sampler2DArray LfxVlmShRedHigh;
uniform sampler2DArray LfxVlmShGreenHigh;
uniform sampler2DArray LfxVlmShBlueHigh;

vec3 lfx_forward_vlm_diffuse(vec3 brick, vec3 normal) {
    const vec4 scale0 = vec4(0.488603, 0.488603, 0.488603, 1.092548) / 0.282095;
    const vec4 scale1 = vec4(1.092548, 4.0 * 0.315392, 1.092548, 2.0 * 0.546274) / 0.282095;
    vec4 transfer0 = vec4(vec3(-normal.y, normal.z, -normal.x) * (0.488603 * (2.0 / 3.0)),
                          1.092548 * normal.x * normal.y * 0.25) * scale0;
    vec4 transfer1 = vec4(-1.092548 * normal.y * normal.z, 0.315392 * (3.0 * normal.z * normal.z - 1.0),
                         -1.092548 * normal.x * normal.z, 0.546274 * (normal.x * normal.x - normal.y * normal.y))
                         * (scale1 * 0.25);
    vec3 ambient = lfx_vlm_sample(LfxVlmAmbient, brick).rgb;
    vec3 light = vec3(dot(lfx_vlm_sample(LfxVlmShRed, brick) * 2.0 - 1.0, transfer0),
                      dot(lfx_vlm_sample(LfxVlmShGreen, brick) * 2.0 - 1.0, transfer0),
                      dot(lfx_vlm_sample(LfxVlmShBlue, brick) * 2.0 - 1.0, transfer0));
    light += vec3(dot(lfx_vlm_sample(LfxVlmShRedHigh, brick) * 2.0 - 1.0, transfer1),
                  dot(lfx_vlm_sample(LfxVlmShGreenHigh, brick) * 2.0 - 1.0, transfer1),
                  dot(lfx_vlm_sample(LfxVlmShBlueHigh, brick) * 2.0 - 1.0, transfer1));
    return max(vec3(0.0), ambient * (vec3(0.282095) + light)) * ForwardPrecomputedScale.rgb;
}
#endif

vec3 lfx_scene_indirect_diffuse(vec3 position, vec3 sourceNormal, vec3 baseColor, vec3 diffuseColor, float ao,
                               bool backface, vec3 subsurfaceColor, out vec3 precomputedIrradiance) {
    vec3 skyNormal = sourceNormal;
    float visibility = 1.0;
    float geometry = 1.0;
    vec3 irradiance = vec3(0.0);
    vec3 backIrradiance = vec3(0.0);
#if LFX_FORWARD_VOLUMETRIC_LIGHTMAP || LFX_OPAQUE_VOLUMETRIC_LIGHTMAP
    vec3 brick;
    if (lfx_vlm_coordinate(position.xzy, brick)) {
        irradiance = lfx_forward_vlm_diffuse(brick, sourceNormal);
        if (backface) backIrradiance = lfx_forward_vlm_diffuse(brick, -sourceNormal);
        if (ForwardPrecomputedScale.w != 0.0) {
            vec3 bent = lfx_vlm_sample(LfxVlmSkyBent, brick).rgb * 2.0 - 1.0;
            visibility = length(bent);
            bent /= max(visibility, 0.0001);
            float weight = 1.0 - (1.0 - visibility) * (1.0 - visibility);
            skyNormal = mix(bent, sourceNormal, weight);
            geometry = mix(clamp(dot(bent, sourceNormal), 0.0, 1.0), 1.0, weight);
        }
    }
#endif
    if (ForwardSkyColor.w != 0.0) {
        irradiance += lfx_forward_sky_diffuse(skyNormal) * (visibility * geometry);
        if (backface) backIrradiance += lfx_forward_sky_diffuse(-sourceNormal) * visibility;
    }
#if LFX_FORWARD_VOLUMETRIC_LIGHTMAP || LFX_OPAQUE_VOLUMETRIC_LIGHTMAP
    precomputedIrradiance = irradiance;
#else
    precomputedIrradiance = vec3(0.0);
#endif
    return (irradiance * diffuseColor + backIrradiance * subsurfaceColor) * lfx_indirect_ao_multibounce(baseColor, ao);
}
