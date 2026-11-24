#version 330
#ifdef LFX_CSM_PARTICLE_RIBBON
#define LFX_CSM_CASTER_VERTEX
#moj_import <lumenfx:lighting/directional_shadow_caster.glsl>
#endif
#define LFX_VERTEX_STAGE 1
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <lumenfx:lighting/translucency_volume_material.glsl>
#moj_import <lumenfx:lighting/particle_point_shadow.glsl>
#moj_import <lumenfx:particle_motion.glsl>
#if LFX_APPLY_FOG && !LFX_PIXEL_FOG
#moj_import <lumenfx:scene_fog.glsl>
out vec4 effectFog;
#endif

layout(std140) uniform EffectView {
    vec4 CameraRight;
    vec4 CameraUp;
    vec4 Geometry;
#if LFX_DEPTH_PROJECTION
    vec4 DepthTransform;
#endif
};
in vec3 CenterA;
in vec3 CenterB;
in vec3 OffsetA;
in vec3 OffsetB;
in vec4 UvRanges;
in vec4 UvTransform;
in vec4 ColorA;
in vec4 ParticleA;
in vec4 CustomA;
in vec4 ColorB;
in vec4 ParticleB;
in vec4 CustomB;
in vec4 RibbonControlVRange;
in vec4 RibbonVRange;
in vec4 RibbonVRangeCenterA;
in vec3 ParticleCenterB;
out vec2 effectUV;
out vec2 effectUV1;
out vec4 effectColor;
out vec4 effectParticle;
out vec3 effectParticleCenter;
out vec4 effectCustom;
out float effectDynamicValid;
#if LFX_EXTRA_MATERIAL || LFX_RIBBON_PARTICLE_DATA || LFX_PARTICLE_MOTION || LFX_MATERIAL_BASIS || defined(LFX_CSM_PARTICLE_RIBBON)
layout(std140) uniform NiagaraExtraInfo { ivec4 ExtraInfo; };
uniform samplerBuffer NiagaraExtraData;
#endif
#if LFX_EXTRA_MATERIAL
out vec4 effectCustom1;
out vec4 effectCustom2;
out vec4 effectCustom3;
#endif
#if LFX_EXTRA_MATERIAL || LFX_RIBBON_PARTICLE_DATA || LFX_PARTICLE_MOTION || LFX_MATERIAL_BASIS || defined(LFX_CSM_PARTICLE_RIBBON)
vec4 lfx_read_extra(int base) {
    return vec4(texelFetch(NiagaraExtraData, base).r, texelFetch(NiagaraExtraData, base + 1).r,
                texelFetch(NiagaraExtraData, base + 2).r, texelFetch(NiagaraExtraData, base + 3).r);
}
#endif
out vec3 effectPosition;
#if LFX_RIBBON_PARTICLE_DATA
out vec4 effectRibbonVelocity;
out vec2 effectRibbonSize;
#endif

#moj_import <lumenfx:material_sg_uv4.glsl>
#moj_import <lumenfx:material_vertex.glsl>
void main() {
    int segment = gl_VertexID / 6;
    int corner = gl_VertexID % 6;
    bool end = corner == 2 || corner == 4 || corner == 5;
    uint instanceControl = uint(RibbonControlVRange.x);
    bool explicitCorners = (instanceControl & 0x10000u) != 0u;
    bool alternateDiagonal = explicitCorners && (instanceControl & 0x20000u) != 0u;
    bool upper = alternateDiagonal ? (corner == 1 || corner == 3 || corner == 4) : (corner == 1 || corner == 2 || corner == 4);
    float v = (float(segment) + (upper ? 1.0 : 0.0)) / Geometry.x;
    vec3 position = explicitCorners ? (upper ? (end ? OffsetB : OffsetA) : (end ? CenterB : CenterA))
            : (end ? CenterB : CenterA) + (end ? OffsetB : OffsetA) * (v * 2.0 - 1.0);
    vec3 particleCenter = end ? ParticleCenterB : RibbonVRangeCenterA.yzw;
    effectParticleCenter = particleCenter.xzy * 100.0;
    vec4 vRange = end ? vec4(RibbonVRange.yzw, RibbonVRangeCenterA.x) : vec4(RibbonControlVRange.yzw, RibbonVRange.x);
    float v0 = explicitCorners ? (upper ? vRange.y : vRange.x) : v * vRange.x + vRange.y;
    float v1 = explicitCorners ? (upper ? vRange.w : vRange.z) : v * vRange.z + vRange.w;
    effectUV = vec2(end ? UvRanges.y : UvRanges.x, v0 * UvTransform.x + UvTransform.y);
    effectUV1 = vec2(end ? UvRanges.w : UvRanges.z, v1 * UvTransform.z + UvTransform.w);
    effectColor = end ? ColorB : ColorA;
    effectParticle = end ? ParticleB : ParticleA;
    effectCustom = end ? CustomB : CustomA;
#if LFX_EXTRA_MATERIAL
    int extraBase = ExtraInfo.x + LFX_MOTION_INSTANCE_ID * ExtraInfo.y + (end ? 12 : 0);
    effectCustom1 = lfx_read_extra(extraBase);
    effectCustom2 = lfx_read_extra(extraBase + 4);
    effectCustom3 = lfx_read_extra(extraBase + 8);
#endif
#if LFX_RIBBON_PARTICLE_DATA
    int particleBase = ExtraInfo.x + LFX_MOTION_INSTANCE_ID * ExtraInfo.y + 24 + (end ? 4 : 0);
    vec4 materialData = lfx_read_extra(particleBase);
    if (explicitCorners) {
        vec3 sourceVelocity = materialData.xzy;
        float squaredSpeed = dot(sourceVelocity, sourceVelocity);
        float inverseSpeed = squaredSpeed > 0.0 ? inversesqrt(squaredSpeed) : 0.0;
        effectRibbonVelocity = squaredSpeed > 0.0 ? vec4(sourceVelocity * inverseSpeed, 1.0 / inverseSpeed) : vec4(0.0);
    } else {
        float speed = length(materialData.xyz);
        vec3 particleDirection = speed > 0.0 ? materialData.xyz / speed : vec3(0.0);
        effectRibbonVelocity = vec4(particleDirection.x, particleDirection.z, particleDirection.y, speed);
    }
    effectRibbonSize = vec2(materialData.w);
#endif
    effectDynamicValid = float(instanceControl & 0xffffu);
    vec3 positionNoOffsets = position;
    lfx_sg_assign_uv4_pair(effectUV, effectUV1);
#if LFX_VERTEX_MATERIAL
    effectPositionNoOffsets = position;
#endif
#if LFX_MATERIAL_BASIS || defined(LFX_CSM_PARTICLE_RIBBON)
    int materialBasisBase = ExtraInfo.x + LFX_MOTION_INSTANCE_ID * ExtraInfo.y + ExtraInfo.w;
    int materialEnd = end ? 12 : 0;
    vec4 materialDirection = lfx_read_extra(materialBasisBase + materialEnd);
    vec4 materialRight = lfx_read_extra(materialBasisBase + materialEnd + 4);
    vec3 materialFacing = lfx_read_extra(materialBasisBase + materialEnd + 8).xyz;
    vec4 materialSlice = lfx_read_extra(materialBasisBase + 24);
    vec2 materialSliceNormal = explicitCorners ? (upper ? materialSlice.zw : materialSlice.xy) : mix(materialSlice.xy, materialSlice.zw, v);
    bool geometryNormals = lfx_read_extra(materialBasisBase).w > 0.5;
#ifdef LFX_CSM_PARTICLE_RIBBON
    vec3 shadowNormal = lfx_ribbon_world_normal(materialDirection.xyz, materialRight.xyz, materialFacing, materialSliceNormal, geometryNormals);
#endif
#if LFX_MATERIAL_BASIS
    vec3 materialCenter = particleCenter;
    bool flipToView = lfx_read_extra(materialBasisBase + 4).w > 0.5;
    lfx_ribbon_material_basis(materialCenter, materialDirection.xyz, materialRight.xyz, materialFacing, materialSliceNormal,
            geometryNormals, flipToView, lfxVertexMaterialNormal, lfxVertexMaterialTangent);
    lfxVertexMaterialBinormal = cross(lfxVertexMaterialNormal, lfxVertexMaterialTangent);
    lfxVertexMaterialBasisDeterminant = 1.0;
#endif
#endif
#if LFX_VERTEX_MATERIAL
#if LFX_PRESKIN_POSITION
    lfxVertexPreSkinnedPosition = effectParticleCenter;
#endif
#if LFX_PRESKIN_NORMAL
    lfxVertexPreSkinnedNormal = lfxVertexMaterialNormal;
#endif
    position += lfx_material_vertex_current(position, effectParticleCenter);
#if LFX_MATERIAL_FIRST_PERSON_ROOT
    position = lfx_material_first_person_current(position);
#endif
#endif
    effectPosition = position;
#if LFX_TRANSLUCENCY_LIGHTING && LFX_TLV_VERTEX > 0 && !LFX_PARTICLE_MOTION
    lfx_tlv_vertex(position);
#endif
#if LFX_APPLY_FOG && !LFX_PIXEL_FOG
    effectFog = lfx_vertex_fog(position);
#endif
    gl_Position = lfx_particle_raster_position(position);
#ifdef LFX_CSM_PARTICLE_RIBBON
    gl_Position = lfx_csm_vertex_clip(gl_Position, abs(dot(shadowNormal, CsmCasterLightDirection.xyz)));
#endif
#if LFX_PARTICLE_MOTION
    int motionBase = ExtraInfo.x + LFX_MOTION_INSTANCE_ID * ExtraInfo.y + 32;
    vec4 oldCenter = lfx_read_extra(motionBase + (end ? 8 : 0));
    int motionMode = int(oldCenter.w);
#if LFX_OPAQUE_MOTION
    if (ExtraInfo.z == 0) motionMode = 0;
#endif
    vec3 previousPosition = positionNoOffsets;
    if (motionMode == 1) {
        vec3 oldOther = lfx_read_extra(motionBase + (end ? 12 : 4)).xyz;
        previousPosition = explicitCorners ? (upper ? oldOther : oldCenter.xyz) : oldCenter.xyz + oldOther * (v * 2.0 - 1.0);
    } else if (motionMode == 2) {
        vec3 translation = lfx_read_extra(motionBase + (end ? 20 : 16)).xyz * (MotionClock.x * 0.01);
        previousPosition -= translation;
    }
    previousPosition += MotionCameraDelta.xzy * 0.01;
#if LFX_VERTEX_MATERIAL
    previousPosition += lfx_material_vertex_previous(previousPosition, effectParticleCenter);
#if LFX_MATERIAL_FIRST_PERSON_ROOT
    previousPosition = lfx_material_first_person_previous(previousPosition);
#endif
#endif
    lfxMotionShader_currentClip = gl_Position;
    lfxMotionShader_previousClip = MotionPreviousViewProjection * vec4(previousPosition, 1.0);
    lfxMotionShader_validity = MotionCameraDelta.w;
#endif
#if LFX_TRANSLUCENT_MOTION_LOG
    lfxMotionShader_instanceId = uint(gl_InstanceID);
#endif
}
