#version 330
#define LFX_VERTEX_STAGE 1
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <lumenfx:particle_motion.glsl>
#if LFX_APPLY_FOG && !LFX_PIXEL_FOG
#moj_import <lumenfx:scene_fog.glsl>
out vec4 effectFog;
#endif

layout(std140) uniform EffectView {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 Geometry;
#if LFX_MACRO_UV
    vec4 MacroUV;
#endif
#if LFX_DEPTH_PROJECTION
    vec4 DepthTransform;
#endif
};
in vec3 Center;
in vec3 SizeRotation;
in vec4 AtlasUV;
in vec4 Color;
in vec4 ParticleData;
in vec4 MaterialData;
in vec4 AtlasNextUV;
in vec4 SubUVControl;
in float DynamicValid;
in vec3 SpriteVelocity;
in vec3 SpriteFacing;
in vec3 SpriteAlignment;
in vec4 SpriteConfig;
in vec3 SpritePivotBlend;
in float SpriteSubImage;
out vec2 effectUV;
out vec2 effectUV1;
out vec2 effectNextUV;
out vec2 effectUnflippedUV;
out vec2 effectNextUnflippedUV;
out vec4 effectColor;
out vec4 effectParticle;
flat out vec3 effectParticleCenter;
flat out float effectSpriteRotation;
out vec4 effectCustom;
out float effectDynamicValid;
#if LFX_EXTRA_MATERIAL || LFX_PARTICLE_MOTION
layout(std140) uniform NiagaraExtraInfo { ivec4 ExtraInfo; };
uniform samplerBuffer NiagaraExtraData;
out vec4 effectCustom1;
out vec4 effectCustom2;
out vec4 effectCustom3;
vec4 lfx_read_extra(int base) {
    return vec4(texelFetch(NiagaraExtraData, base).r, texelFetch(NiagaraExtraData, base + 1).r,
                texelFetch(NiagaraExtraData, base + 2).r, texelFetch(NiagaraExtraData, base + 3).r);
}
#endif
out float effectSubImageLerp;
out vec3 effectPosition;
flat out vec4 effectSpriteVelocity;
flat out vec2 effectSpriteSize;
#moj_import <lumenfx:niagara_sprite_main.glsl>
