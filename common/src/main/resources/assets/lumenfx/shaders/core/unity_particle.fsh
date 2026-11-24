#version 330
#moj_import <lumenfx:material_fog.glsl>
#moj_import <lumenfx:lighting/unity_brdf_inputs.glsl>
#moj_import <lumenfx:srgb.glsl>
#if (LFX_PARTICLE_SURFACE || LFX_FORWARD_TRANSLUCENCY) && !LFX_PARTICLE_MOTION && LFX_UNITY_PARTICLE_LIT
#moj_import <lumenfx:lighting/default_lit_surface.glsl>
#moj_import <lumenfx:lighting/unity_metallic_forward.glsl>
#define LFX_UNITY_LIT_OUTPUT 1
in vec3 lfxUnityParticleNormal;
in vec3 lfxUnityParticleTangent;
in vec3 lfxUnityParticleBitangent;
#else
#define LFX_UNITY_LIT_OUTPUT 0
#endif
#if LFX_HAS_SAMPLER0
uniform sampler2D Sampler0;
#endif
#if LFX_HAS_SAMPLER1
uniform sampler2D Sampler1;
#endif
#if LFX_HAS_SAMPLER2
uniform sampler2D Sampler2;
#endif
#if LFX_HAS_SAMPLER3
uniform sampler2D Sampler3;
#endif
layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; };
#if LFX_DEPTH_PROJECTION || LFX_SCENE_COLOR
layout(std140) uniform EffectView {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 Geometry;
#if LFX_MACRO_UV
    vec4 MacroUV;
#endif
    vec4 DepthTransform;
};
#endif
#if LFX_SCENE_COLOR
#moj_import <lumenfx:material_view.glsl>
#moj_import <lumenfx:material_scene_color.glsl>
#endif
#if LFX_DEPTH_PROJECTION
float lfx_linear_depth(float depth) {
    return depth * DepthTransform.x + DepthTransform.y + 1.0 / (depth * DepthTransform.z - DepthTransform.w);
}
#endif
#if LFX_SCENE_DEPTH
uniform sampler2D SceneDepthSampler;
#endif
#if LFX_MATERIAL_INSTANCE
layout(std140) uniform MaterialInstance { vec4 LfxMaterialParameterValues[4]; };
#endif

in vec2 effectUV;
in vec2 effectUV1;
in vec4 effectColor;
in vec4 effectParticle;
in vec4 effectCustom;
#if LFX_KIND_SPRITE
in vec2 effectNextUV;
in float effectSubImageLerp;
#elif LFX_KIND_MESH
in vec2 effectMeshSubUV1;
in float effectSubImageLerp;
#endif

#if LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
#define LFX_COLOR_OUTPUT layout(location = 0, index = 0)
#define LFX_TRANSMITTANCE_OUTPUT layout(location = 0, index = 1) out
#elif LFX_PARTICLE_GAMMA_TARGET && !LFX_PARTICLE_MOTION
#define LFX_COLOR_OUTPUT layout(location = 0)
#define LFX_TRANSMITTANCE_OUTPUT
#else
#define LFX_COLOR_OUTPUT
#define LFX_TRANSMITTANCE_OUTPUT
#endif
LFX_COLOR_OUTPUT out vec4 fragColor;
LFX_TRANSMITTANCE_OUTPUT vec4 lfxDualTransmittance;
#if LFX_PARTICLE_GAMMA_TARGET && !LFX_PARTICLE_MOTION
layout(location = 1) out uint lfxGammaCoverage;
#endif

vec3 lfx_rgb_to_hsl(vec3 c) {
    float maxC = max(c.r, max(c.g, c.b));
    float minC = min(c.r, min(c.g, c.b));
    float l = (maxC + minC) * 0.5;
    if (maxC == minC) return vec3(0.0, 0.0, l);
    float d = maxC - minC;
    float s = l > 0.5 ? d / max(2.0 - maxC - minC, 1e-6) : d / max(maxC + minC, 1e-6);
    float h;
    if (maxC == c.r) h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
    else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return vec3(h / 6.0, s, l);
}

float lfx_hue_to_rgb(float p, float q, float t) {
    t = fract(t);
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 0.5) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

vec3 lfx_hsl_to_rgb(vec3 hsl) {
    if (hsl.y <= 0.0) return vec3(hsl.z);
    float q = hsl.z < 0.5 ? hsl.z * (1.0 + hsl.y) : hsl.z + hsl.y - hsl.z * hsl.y;
    float p = 2.0 * hsl.z - q;
    return vec3(
        lfx_hue_to_rgb(p, q, hsl.x + 1.0 / 3.0),
        lfx_hue_to_rgb(p, q, hsl.x),
        lfx_hue_to_rgb(p, q, hsl.x - 1.0 / 3.0)
    );
}

vec4 lfx_apply_color_mode(vec4 albedo, vec4 vertexColor, int mode) {
    if (mode == 1) return vec4(albedo.rgb + vertexColor.rgb, albedo.a * vertexColor.a);
    if (mode == 2) return vec4(albedo.rgb - vertexColor.rgb, albedo.a * vertexColor.a);
    if (mode == 3) {
        vec3 low = 2.0 * albedo.rgb * vertexColor.rgb;
        vec3 high = 1.0 - 2.0 * (1.0 - albedo.rgb) * (1.0 - vertexColor.rgb);
        return vec4(mix(low, high, step(vec3(0.5), albedo.rgb)), albedo.a * vertexColor.a);
    }
    if (mode == 4) {
        vec3 aHsl = lfx_rgb_to_hsl(clamp(albedo.rgb, 0.0, 1.0));
        vec3 vHsl = lfx_rgb_to_hsl(clamp(vertexColor.rgb, 0.0, 1.0));
        return vec4(lfx_hsl_to_rgb(vec3(vHsl.x, vHsl.y, aHsl.z)), albedo.a * vertexColor.a);
    }
    if (mode == 5) return vec4(abs(albedo.rgb - vertexColor.rgb), albedo.a * vertexColor.a);
    return albedo * vertexColor;
}

vec4 lfx_unity_sample_flipbook(sampler2D image, vec2 uv, vec4 uvST, bool blendFrames) {
    vec4 current = texture(image, uv);
#if LFX_KIND_SPRITE || LFX_KIND_MESH
    if (blendFrames && effectSubImageLerp > 0.0) {
#if LFX_KIND_SPRITE
        vec2 nextUv = effectNextUV;
#else
        vec2 nextUv = effectMeshSubUV1;
#endif
        vec4 nextFrame = texture(image, nextUv * uvST.xy + uvST.zw);
        return mix(current, nextFrame, clamp(effectSubImageLerp, 0.0, 1.0));
    }
#endif
    return current;
}

void main() {
#if LFX_MATERIAL_INSTANCE
    vec4 uvST = LfxMaterialParameterValues[0];
    vec4 emissionAndCutoff = LfxMaterialParameterValues[1];
    vec4 fadeParams = LfxMaterialParameterValues[2];
    vec4 config = LfxMaterialParameterValues[3];
#else
    vec4 uvST = vec4(1.0, 1.0, 0.0, 0.0);
    vec4 emissionAndCutoff = vec4(0.0, 0.0, 0.0, -1.0);
    vec4 fadeParams = vec4(0.0);
    vec4 config = vec4(0.0, 1.0, 0.0, 0.0);
#endif
    int flags = int(config.y + 0.5);
    bool hasMainTex = (flags & 1) != 0;
    bool flipbookBlend = (flags & 2) != 0;
    bool hasEmissionMap = (flags & 4) != 0;
    bool cullFront = (flags & 8) != 0;
    bool fadeRgbWithAlpha = (flags & 16) != 0;
    bool premultiplyOutput = (flags & 32) != 0;
    bool alphaModulate = (flags & 64) != 0;
    bool hasNormalMap = (flags & 128) != 0;
    bool hasDistortion = (flags & 256) != 0;
    bool litSurface = (flags & 512) != 0;
    bool urpDistortion = (flags & 2048) != 0;
    bool urpUnlit = (flags & 4096) != 0;
    bool premultiplyLitDiffuse = (flags & 8192) != 0;
    bool gammaOpaque = (flags & 16384) != 0;

    if (cullFront && gl_FrontFacing) discard;

    vec2 uv = effectUV * uvST.xy + uvST.zw;
#if LFX_HAS_SAMPLER0
    vec4 albedo = hasMainTex ? lfx_unity_sample_flipbook(Sampler0, uv, uvST, flipbookBlend) : vec4(1.0);
#else
    vec4 albedo = vec4(1.0);
#endif

    vec4 color = lfx_apply_color_mode(albedo, effectColor, int(config.x + 0.5));

    vec3 surfNormal = vec3(0.0, 0.0, 1.0);
#if LFX_HAS_SAMPLER2
    if (hasNormalMap || hasDistortion) {
        vec4 sampleValue = lfx_unity_sample_flipbook(Sampler2, uv, uvST, flipbookBlend);
        if ((flags & 1024) != 0) {
            // Unity UnpackNormalmapRGorAG accepts BC5 and DXT5nm after filtering the packed channels.
            vec2 xy = vec2(sampleValue.r * sampleValue.a, sampleValue.g) * 2.0 - 1.0;
            float z = max(1.0e-16, sqrt(1.0 - min(dot(xy, xy), 1.0)));
            surfNormal = vec3(xy * config.z, z);
        } else {
            vec2 xy = sampleValue.rg * 2.0 - 1.0;
            xy *= config.z != 0.0 ? config.z : 1.0;
            surfNormal = normalize(vec3(xy, sqrt(max(1.0 - dot(xy, xy), 0.0))));
        }
    }
#endif
#if LFX_SCENE_COLOR
    if (hasDistortion && !urpDistortion) {
        vec2 refractUv = lfx_scene_color_offset_uv(surfNormal.xy * config.w * max(color.a, 0.05));
        vec3 sceneRgb = lfx_material_scene_color(refractUv).rgb;
        if (gammaOpaque || LFX_PARTICLE_GAMMA_TARGET != 0) sceneRgb = linearToSrgb(sceneRgb);
        color.rgb = mix(sceneRgb, color.rgb * sceneRgb, clamp(color.a, 0.0, 1.0));
    }
#endif
#if !LFX_UNITY_LIT_OUTPUT && !LFX_UNITY_PARTICLE_LIT
    if (litSurface) {
        float ndotl = clamp(dot(normalize(surfNormal), normalize(vec3(0.3, 0.7, 0.65))), 0.0, 1.0);
        color.rgb *= (0.4 + 0.6 * ndotl);
    }
#endif

    float cutoff = emissionAndCutoff.a;
    if (cutoff >= 0.0 && color.a < cutoff) discard;
    if (urpUnlit) {
        if (alphaModulate) color.rgb = mix(vec3(1.0), color.rgb, clamp(color.a, 0.0, 1.0));
        else if (premultiplyOutput) color.rgb *= color.a;
    }

    float fade = 1.0;
#if LFX_DEPTH_PROJECTION
    float partZ = lfx_linear_depth(gl_FragCoord.z) * 0.01;
#if LFX_SCENE_DEPTH
    if (fadeParams.y > 0.0) {
        float sceneZ = lfx_linear_depth(texelFetch(SceneDepthSampler, ivec2(gl_FragCoord.xy), 0).r) * 0.01;
        fade *= clamp((sceneZ - partZ - fadeParams.x) * fadeParams.y, 0.0, 1.0);
    }
#endif
    if (fadeParams.w > 0.0) {
        fade *= clamp((partZ - fadeParams.z) * fadeParams.w, 0.0, 1.0);
    }
#endif

    color.a *= fade;
    if (fadeRgbWithAlpha || (LFX_UNITY_LIT_OUTPUT != 0 && premultiplyLitDiffuse)) color.rgb *= fade;
#if LFX_SCENE_COLOR
    if (urpDistortion) {
        vec2 viewport = lfx_scene_color_host_uv(lfx_scene_color_default_uv());
        vec2 offset = surfNormal.xy * config.w * color.a;
        vec3 sceneRgb = lfx_material_scene_color(lfx_scene_color_uv(viewport + offset)).rgb;
        if (gammaOpaque || LFX_PARTICLE_GAMMA_TARGET != 0) sceneRgb = linearToSrgb(sceneRgb);
        color.rgb = mix(sceneRgb, color.rgb, clamp(color.a - MaterialParameters.z, 0.0, 1.0));
    }
#endif

    vec3 emission = emissionAndCutoff.rgb;
#if LFX_HAS_SAMPLER1
    if (hasEmissionMap) emission *= lfx_unity_sample_flipbook(Sampler1, uv, uvST, flipbookBlend).rgb;
#endif
#if LFX_UNITY_LIT_OUTPUT
    vec2 metallicGloss = MaterialParameters.xy;
#if LFX_HAS_SAMPLER3
    metallicGloss = lfx_unity_sample_flipbook(Sampler3, uv, uvST, flipbookBlend).ra * vec2(1.0, metallicGloss.y);
#endif
    vec3 worldNormal = normalize(surfNormal.x * lfxUnityParticleTangent
            + surfNormal.y * lfxUnityParticleBitangent + surfNormal.z * lfxUnityParticleNormal);
#if LFX_PARTICLE_SURFACE
    lfx_write_unity_urp_surface(color.rgb, metallicGloss.x, vec3(0.0), false,
            1.0 - clamp(metallicGloss.y, 0.0, 1.0), 1.0, worldNormal, effectPosition);
    fragColor = lfx_apply_material_fog(vec4(emission, 1.0));
#else
    float diffuseAlpha = premultiplyLitDiffuse ? color.a : 1.0;
    vec3 litAlbedo = color.rgb;
    if (alphaModulate) litAlbedo = mix(vec3(1.0), litAlbedo, clamp(color.a, 0.0, 1.0));
    vec3 lighting = lfx_unity_coated_forward(effectPosition, worldNormal, litAlbedo,
            metallicGloss.x, vec3(0.0), false, metallicGloss.y, 1.0, diffuseAlpha, 0.0, 0.0);
    fragColor = lfx_apply_material_fog(vec4(lighting + emission, color.a));
#endif
#else
#if LFX_UNITY_PARTICLE_LIT && !LFX_PARTICLE_MOTION
    // With no active lighting pass a URP surface only contributes its emission.
    color = vec4(emission, 1.0);
#else
    color.rgb += emission * (fadeRgbWithAlpha && !urpUnlit ? fade : 1.0);
#endif

    vec4 outColor = premultiplyOutput && !urpUnlit ? vec4(color.rgb * color.a, color.a) : color;
    if (alphaModulate && !urpUnlit) outColor.rgb = mix(vec3(1.0), outColor.rgb, clamp(outColor.a, 0.0, 1.0));
    if (gammaOpaque) outColor.rgb = srgbToLinear(outColor.rgb);
    outColor = lfx_apply_material_fog(outColor);
    vec3 transmittance = clamp(vec3(1.0) - outColor.rgb, vec3(0.0), vec3(1.0));
#if LFX_SUBSTRATE_MODULATION_ONLY && !LFX_PARTICLE_MOTION && !LFX_UNITY_PARTICLE_MULTIPLY
    fragColor = vec4(transmittance, 1.0);
#elif LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
    fragColor = vec4(outColor.rgb, 0.0);
    lfxDualTransmittance = vec4(transmittance, 1.0);
#else
    fragColor = outColor;
#endif
#endif
#if LFX_PARTICLE_GAMMA_TARGET && !LFX_PARTICLE_MOTION
    lfxGammaCoverage = 1u;
#endif
}
