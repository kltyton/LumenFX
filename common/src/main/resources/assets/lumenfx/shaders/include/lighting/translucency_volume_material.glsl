#if LFX_TRANSLUCENCY_LIGHTING && !LFX_PARTICLE_MOTION
#moj_import <lumenfx:lighting/translucency_volume_grid.glsl>
#if LFX_TLV_INDIRECT
#if LFX_VERTEX_STAGE
#if LFX_TLV_VLM
#moj_import <lumenfx:lighting/volumetric_lightmap.glsl>
#endif
#else
#moj_import <lumenfx:lighting/scene_indirect_diffuse.glsl>
#moj_import <lumenfx:lighting/material_bent_normal.glsl>
#endif
#endif

uniform sampler2D TlvAmbientInner;
uniform sampler2D TlvAmbientOuter;
uniform sampler2D TlvDirectionInner;
uniform sampler2D TlvDirectionOuter;

vec3 lfx_tlv_uv(vec3 position, vec4 cascade) {
    return cascade.w > 0.0 ? (position - cascade.xyz) / (cascade.w * float(TlvAtlas.x)) : vec3(-1.0);
}

void lfx_tlv_coefficients(vec3 position, vec3 voxelOffset, bool directional, out vec3 ambient, out vec3 direction) {
    vec3 worldCm = position * 100.0;
    vec3 inner = lfx_tlv_uv(worldCm + voxelOffset * TlvCascade[0].w, TlvCascade[0]);
    vec3 outer = lfx_tlv_uv(worldCm + voxelOffset * TlvCascade[1].w, TlvCascade[1]);
    vec3 fade = clamp((0.5 - abs(inner - 0.5)) * 6.0, 0.0, 1.0);
    float innerWeight = fade.x * fade.y * fade.z;
    ambient = mix(lfx_tlv_sample(TlvAmbientOuter, outer).rgb, lfx_tlv_sample(TlvAmbientInner, inner).rgb, innerWeight);
    direction = directional ? mix(lfx_tlv_sample(TlvDirectionOuter, outer).rgb,
        lfx_tlv_sample(TlvDirectionInner, inner).rgb, innerWeight) : vec3(0.0);
}

#if LFX_VERTEX_STAGE
#if LFX_TLV_VERTEX > 0
out vec3 lfxTlvAmbient;
#if LFX_TLV_VLM
out vec3 lfxTlvIndirectAmbient;
#if LFX_TLV_VERTEX == 2
out vec3 lfxTlvIndirectRed;
out vec3 lfxTlvIndirectGreen;
out vec3 lfxTlvIndirectBlue;
#endif
#endif
#if LFX_TLV_VERTEX == 2
out vec3 lfxTlvDirection;
#endif

void lfx_tlv_vertex(vec3 position) {
    vec3 direction;
    lfx_tlv_coefficients(position, vec3(0.0), LFX_TLV_VERTEX == 2, lfxTlvAmbient, direction);
#if LFX_TLV_VLM
    vec3 brick;
    bool valid = lfx_vlm_coordinate(position.xzy, brick);
    lfxTlvIndirectAmbient = valid ? lfx_vlm_sample(LfxVlmAmbient, brick).rgb : vec3(0.0);
#if LFX_TLV_VERTEX == 2
    const float scale = 0.488603 / 0.282095;
    lfxTlvIndirectRed = valid ? (lfx_vlm_sample(LfxVlmShRed, brick).xyz * 2.0 - 1.0) * (lfxTlvIndirectAmbient.r * scale) : vec3(0.0);
    lfxTlvIndirectGreen = valid ? (lfx_vlm_sample(LfxVlmShGreen, brick).xyz * 2.0 - 1.0) * (lfxTlvIndirectAmbient.g * scale) : vec3(0.0);
    lfxTlvIndirectBlue = valid ? (lfx_vlm_sample(LfxVlmShBlue, brick).xyz * 2.0 - 1.0) * (lfxTlvIndirectAmbient.b * scale) : vec3(0.0);
#endif
#endif
#if LFX_TLV_VERTEX == 2
    lfxTlvDirection = direction;
#endif
}
#endif
#else
#if LFX_TLV_VERTEX > 0
in vec3 lfxTlvAmbient;
#if LFX_TLV_VLM
in vec3 lfxTlvIndirectAmbient;
#if LFX_TLV_VERTEX == 2
in vec3 lfxTlvIndirectRed;
in vec3 lfxTlvIndirectGreen;
in vec3 lfxTlvIndirectBlue;
#endif
#endif
#if LFX_TLV_VERTEX == 2
in vec3 lfxTlvDirection;
#endif
#else
uniform sampler2D TlvBlueNoiseScalar;
uniform sampler2D TlvBlueNoiseVector;

vec3 lfx_tlv_pixel_offset(vec3 spriteOffset) {
    if (TlvNoise.x <= 0.0) return spriteOffset;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (TlvNoise.z > 0.0) pixel.y = int(TlvNoise.z) - 1 - pixel.y;
    ivec2 texel = (pixel & ivec2(127)) + ivec2(0, int(TlvNoise.y) * 128);
    vec3 random = vec3(texelFetch(TlvBlueNoiseVector, texel, 0).rg, texelFetch(TlvBlueNoiseScalar, texel, 0).r);
    return (random.xzy - 0.5) * TlvNoise.x;
}
#endif

vec3 lfx_tlv_sprite_offset(float particleRandom) {
    float seed = particleRandom * 47.0 + 10.0;
    vec3 ueOffset = fract((seed * seed) * vec3(1361.456345, 2333.578, 3623.983)) - 0.5;
    return ueOffset.xzy;
}

vec3 lfx_tlv_diffuse(vec3 position, vec3 normal, vec3 diffuseColor, float directionalIntensity, bool directional,
                    vec3 voxelOffset, vec3 indirectColor, vec3 baseColor, float ambientOcclusion, vec3 worldNormal,
                    vec3 bentNormal, bool hasBentNormal, vec3 view) {
    vec3 ambient, direction;
#if LFX_TLV_VERTEX == 2
    ambient = lfxTlvAmbient;
    direction = lfxTlvDirection;
#elif LFX_TLV_VERTEX == 1
    ambient = lfxTlvAmbient;
    direction = vec3(0.0);
#else
    lfx_tlv_coefficients(position, lfx_tlv_pixel_offset(voxelOffset), directional, ambient, direction);
#endif
    vec3 lighting = ambient * (0.282095 * 3.14159265);
    if (directional) {
        vec3 transfer = 0.488603 * (2.0 * 3.14159265 / 3.0) * vec3(-normal.y, normal.z, -normal.x) * directionalIntensity;
        float luminance = dot(ambient, LFX_TLV_LUMA);
        lighting = max(lighting + ambient * (dot(direction, transfer) / (luminance + 0.00001)), vec3(0.0));
    }
#if LFX_TLV_INDIRECT
    LfxShadingOcclusion occlusion = lfx_material_shading_occlusion(worldNormal, bentNormal, view, 0.5, ambientOcclusion, hasBentNormal);
    vec3 indirectNormal = occlusion.bentNormal;
#endif
#if LFX_TLV_VLM
    vec3 indirect;
#if LFX_TLV_VERTEX > 0
    indirect = lfxTlvIndirectAmbient * 0.282095;
#if LFX_TLV_VERTEX == 2
    vec3 transfer = 0.488603 * (2.0 / 3.0) * vec3(-indirectNormal.y, indirectNormal.z, -indirectNormal.x);
    indirect += vec3(dot(lfxTlvIndirectRed, transfer), dot(lfxTlvIndirectGreen, transfer), dot(lfxTlvIndirectBlue, transfer));
#endif
#else
    vec3 brick;
    indirect = vec3(0.0);
    if (lfx_vlm_coordinate(position.xzy, brick)) {
        vec3 ambientVlm = lfx_vlm_sample(LfxVlmAmbient, brick).rgb;
        indirect = ambientVlm * 0.282095;
        if (directional) {
            vec3 transfer = (0.488603 * (2.0 / 3.0)) * vec3(-indirectNormal.y, indirectNormal.z, -indirectNormal.x);
            vec3 scale = ambientVlm * (0.488603 / 0.282095);
            indirect += scale * vec3(dot(lfx_vlm_sample(LfxVlmShRed, brick).xyz * 2.0 - 1.0, transfer),
                                     dot(lfx_vlm_sample(LfxVlmShGreen, brick).xyz * 2.0 - 1.0, transfer),
                                     dot(lfx_vlm_sample(LfxVlmShBlue, brick).xyz * 2.0 - 1.0, transfer));
        }
    }
#endif
    vec3 indirectLighting = max(indirect, vec3(0.0)) * ForwardPrecomputedScale.rgb;
#else
    vec3 indirectLighting = vec3(0.0);
#endif
#if LFX_TLV_INDIRECT
    if (ForwardSkyColor.w != 0.0) {
        vec3 skyNormal = indirectNormal;
        float visibility = 1.0, geometry = 1.0;
#if LFX_TLV_VLM
        vec3 skyBrick;
        if (ForwardPrecomputedScale.w != 0.0 && lfx_vlm_coordinate(position.xzy, skyBrick)) {
            vec3 bent = lfx_vlm_sample(LfxVlmSkyBent, skyBrick).rgb * 2.0 - 1.0;
            visibility = length(bent);
            bent /= max(visibility, 0.0001);
            if (directional) {
                float weight = 1.0 - (1.0 - visibility) * (1.0 - visibility);
                skyNormal = mix(bent, indirectNormal, weight);
                geometry = mix(clamp(dot(bent, indirectNormal), 0.0, 1.0), 1.0, weight);
            } else skyNormal = bent;
        }
#endif
        indirectLighting += lfx_forward_sky_diffuse(skyNormal) * (visibility * geometry);
    }
    return diffuseColor * lighting + indirectColor * indirectLighting * lfx_indirect_ao_multibounce(baseColor, occlusion.diffuse);
#else
    return diffuseColor * lighting;
#endif
}

vec3 lfx_tlv_diffuse(vec3 position, vec3 normal, vec3 diffuseColor, float directionalIntensity, bool directional,
                    vec3 voxelOffset, vec3 indirectColor, vec3 baseColor, float ambientOcclusion, vec3 worldNormal) {
    return lfx_tlv_diffuse(position, normal, diffuseColor, directionalIntensity, directional, voxelOffset,
            indirectColor, baseColor, ambientOcclusion, worldNormal, worldNormal, false, vec3(0.0, 0.0, 1.0));
}

vec3 lfx_tlv_diffuse(vec3 position, vec3 normal, vec3 diffuseColor, float directionalIntensity, bool directional,
                    vec3 voxelOffset) {
    return lfx_tlv_diffuse(position, normal, diffuseColor, directionalIntensity, directional, voxelOffset,
            diffuseColor, diffuseColor, 1.0, normal);
}

vec3 lfx_tlv_diffuse(vec3 position, vec3 normal, vec3 diffuseColor, float directionalIntensity, bool directional) {
    return lfx_tlv_diffuse(position, normal, diffuseColor, directionalIntensity, directional, vec3(0.0));
}
#endif
#endif
