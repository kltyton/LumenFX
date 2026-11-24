#moj_import <lumenfx:lighting/particle_point_shadow.glsl>
#moj_import <lumenfx:lighting/translucency_volume_material.glsl>
#ifdef LFX_CSM_PARTICLE_MESH
#define LFX_CSM_CASTER_VERTEX
#moj_import <lumenfx:lighting/directional_shadow_caster.glsl>
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
layout(std140) uniform NiagaraMeshData {
    vec4 MeshScalePivotSpace;
    vec4 MeshRotation;
    vec4 MeshPivotLocalSpace;
    vec4 MeshFacingAxis;
    vec4 MeshLockedAxisBlend;
    vec4 MeshSubImage;
};
out vec2 effectUV;
out vec2 effectUV1;
out vec4 effectColor;
out vec4 effectParticle;
out vec4 effectCustom;
out float effectDynamicValid;
out vec3 effectParticleCenter;
out vec3 effectPosition;
out vec4 effectMeshUV01;
out vec4 effectMeshUV23;
out vec4 effectMeshUV45;
out vec4 effectMeshUV67;
out vec4 effectVertexColor;
out float effectUnmirrored;
out vec4 effectMeshVelocity;
out vec2 effectMeshSubUV0;
out vec2 effectMeshSubUV1;
out float effectSubImageLerp;
#if !LFX_GPU_NIAGARA || LFX_EXTRA_MATERIAL || LFX_PARTICLE_MOTION
#if !LFX_GPU_NIAGARA
layout(std140) uniform NiagaraExtraInfo { ivec4 ExtraInfo; };
uniform samplerBuffer NiagaraExtraData;
#endif
out vec4 effectCustom1;
out vec4 effectCustom2;
out vec4 effectCustom3;
#if !LFX_GPU_NIAGARA
vec4 lfx_mesh_extra(int base) {
    return vec4(texelFetch(NiagaraExtraData, base).r, texelFetch(NiagaraExtraData, base + 1).r,
                texelFetch(NiagaraExtraData, base + 2).r, texelFetch(NiagaraExtraData, base + 3).r);
}
#endif
#endif

#moj_import <lumenfx:niagara_mesh_scene.glsl>
#moj_import <lumenfx:material_sg_uv4.glsl>
#moj_import <lumenfx:material_vertex.glsl>
void main() {
#if LFX_GPU_NIAGARA
#if LFX_TRANSLUCENT_STEP_INDIRECT_GUARD
    if (LfxMotionStepBaseInfo.z != 0 && uint(LFX_MOTION_INSTANCE_ID) >= texelFetch(LfxMotionStepIndirect, LfxMotionStepBaseInfo.y + 1).r) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
#endif
    lfx_load_mesh_particle();
#endif
#if LFX_MESH_SCENE
#if LFX_GPU_NIAGARA
    int sceneInstance = int(MeshParticleIndex);
#else
    int sceneInstance = LFX_MOTION_INSTANCE_ID;
#endif
    LfxMeshTransform particle = lfx_mesh_scene_transform(sceneInstance, false);
    if (particle.determinantSign == 0.0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
#else
    LfxMeshTransformInput inputData;
    inputData.position = Center;
    inputData.orientation = Orientation;
    inputData.scale = ScaleCamera.xyz;
    inputData.velocity = Velocity.xyz;
    inputData.cameraOffset = ScaleCamera.w;
    inputData.meshScale = MeshScalePivotSpace.xyz;
    inputData.meshRotation = MeshRotation;
    inputData.localSpace = MeshPivotLocalSpace.w > 0.5;
#if LFX_GPU_NIAGARA
    vec3 ownerAxisScale = OwnerScale.xzy;
#else
    int componentScaleBase = (LFX_MOTION_INSTANCE_ID - ExtraInfo.x) * ExtraInfo.y + 36;
    vec4 componentScale = lfx_mesh_extra(componentScaleBase);
    vec3 ownerAxisScale = vec3(OwnerPositionScale.w, componentScale.xy);
#endif
#if LFX_GPU_NIAGARA
    inputData.primitiveLinear = mat3(OwnerRelative0.xyz, OwnerRelative1.xyz, OwnerRelative2.xyz);
    vec3 primitiveInverseScale = OwnerInverseScale.xyz;
#else
    inputData.primitiveLinear = lfx_component_linear(MeshOwnerRotation, ownerAxisScale);
    vec3 primitiveInverseScale = lfx_component_inverse_scale(inputData.primitiveLinear);
#endif
    inputData.primitiveRotation = mat3(inputData.primitiveLinear[0] * primitiveInverseScale.x,
            inputData.primitiveLinear[1] * primitiveInverseScale.y, inputData.primitiveLinear[2] * primitiveInverseScale.z);
    inputData.primitivePosition = OwnerPositionScale.xyz;
    inputData.primitiveScale = ownerAxisScale;
    inputData.worldOffset = vec3(0.0);
    int pivotSpace = int(MeshScalePivotSpace.w);
    inputData.meshOffsetWorld = pivotSpace != 0;
    inputData.meshOffset = MeshPivotLocalSpace.xyz;
    if (pivotSpace == 3 || pivotSpace == 1 && inputData.localSpace)
        inputData.meshOffset = inputData.primitiveLinear * inputData.meshOffset;
    inputData.facingMode = int(MeshFacingAxis.x);
    inputData.lockedAxisEnabled = MeshFacingAxis.y > 0.5;
    inputData.lockedAxisSpace = int(MeshFacingAxis.z);
    inputData.lockedAxis = MeshLockedAxisBlend.xyz;
    inputData.cameraForward = (-cross(CameraRight.xyz, CameraUp.xyz)).xzy;
    inputData.cameraUp = CameraUp.xzy;
    LfxMeshSrt currentSrt = lfx_mesh_srt(inputData);
    LfxMeshTransform particle = lfx_mesh_transform(currentSrt, inputData);
#endif
    LfxMeshVertex vertex = lfx_mesh_vertex(gl_VertexID);
    vec3 position = (particle.linear * vertex.position + particle.position).xzy * 0.01;
    effectParticleCenter = particle.position;
    effectColor = Color;
#if !LFX_GPU_NIAGARA
    // Shuriken mesh batches can multiply the particle tint by the authored mesh vertex color.
    if (Velocity.w > 0.5) effectColor *= vertex.color;
#endif
    effectParticle = vec4(ParticleData.xy, 0.0, 0.0);
    effectCustom = MaterialData;
    effectDynamicValid = ParticleData.w;
    effectVertexColor = vertex.color;
    effectUnmirrored = vertex.binormalSign * particle.determinantSign;
    float speed = length(Velocity.xyz);
    effectMeshVelocity = vec4(speed > 0.0 ? Velocity.xyz / speed : vec3(0.0), speed);
    effectUV = lfx_mesh_uv(gl_VertexID, 0u);
    effectUV1 = lfx_mesh_uv(gl_VertexID, 1u);
    effectMeshUV01 = vec4(effectUV, effectUV1);
    effectMeshUV23 = vec4(lfx_mesh_uv(gl_VertexID, 2u), lfx_mesh_uv(gl_VertexID, 3u));
    effectMeshUV45 = vec4(lfx_mesh_uv(gl_VertexID, 4u), lfx_mesh_uv(gl_VertexID, 5u));
    effectMeshUV67 = vec4(lfx_mesh_uv(gl_VertexID, 6u), lfx_mesh_uv(gl_VertexID, 7u));
    float fraction = fract(ParticleData.z);
    float frameA = ParticleData.z - fraction;
    float frameB = frameA + 1.0;
    vec2 tileA = vec2(frameA - trunc(frameA / MeshSubImage.x) * MeshSubImage.x, floor(frameA * MeshSubImage.z));
    vec2 tileB = vec2(frameB - trunc(frameB / MeshSubImage.x) * MeshSubImage.x, floor(frameB * MeshSubImage.z));
    int subImageFlags = int(MeshLockedAxisBlend.w + 0.5);
    bool subImageBlend = (subImageFlags & 1) != 0;
    int subImageUvMask = (subImageFlags >> 1) == 0 ? 1 : (subImageFlags >> 1);
    bool subUv0 = (subImageUvMask & 1) != 0;
    bool subUv1 = (subImageUvMask & 2) != 0;
    vec2 baseSubUv = subUv0 ? effectUV : effectUV1;
    effectMeshSubUV0 = (tileA + baseSubUv) * MeshSubImage.zw;
    effectMeshSubUV1 = (tileB + baseSubUv) * MeshSubImage.zw;
    effectSubImageLerp = subImageBlend ? fraction : 0.0;
#if !LFX_GPU_NIAGARA
    if (Velocity.w > 0.5) {
        vec4 uvTransform = lfx_mesh_extra((LFX_MOTION_INSTANCE_ID - ExtraInfo.x) * ExtraInfo.y);
        effectMeshSubUV0 = effectMeshSubUV0 * uvTransform.xy + uvTransform.zw;
        effectMeshSubUV1 = effectMeshSubUV1 * uvTransform.xy + uvTransform.zw;
        if (subUv0) effectUV = (tileA + effectUV) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        if (subUv1) effectUV1 = (tileA + effectUV1) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        effectMeshUV01 = vec4(effectUV, effectUV1);
        if ((subImageUvMask & 4) != 0) effectMeshUV23.xy = (tileA + effectMeshUV23.xy) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        if ((subImageUvMask & 8) != 0) effectMeshUV23.zw = (tileA + effectMeshUV23.zw) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        if ((subImageUvMask & 16) != 0) effectMeshUV45.xy = (tileA + effectMeshUV45.xy) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        if ((subImageUvMask & 32) != 0) effectMeshUV45.zw = (tileA + effectMeshUV45.zw) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        if ((subImageUvMask & 64) != 0) effectMeshUV67.xy = (tileA + effectMeshUV67.xy) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        if ((subImageUvMask & 128) != 0) effectMeshUV67.zw = (tileA + effectMeshUV67.zw) * MeshSubImage.zw * uvTransform.xy + uvTransform.zw;
        effectSubImageLerp = fraction;
    }
#endif
#if (LFX_SG_UV4_MASK & 1) != 0
    lfxSgVertexUV0 = lfx_mesh_uv4(gl_VertexID, 0u);
    lfxSgVertexUV0.xy = effectUV;
    lfxSgUV0 = lfxSgVertexUV0;
#endif
#if (LFX_SG_UV4_MASK & 2) != 0
    lfxSgVertexUV1 = lfx_mesh_uv4(gl_VertexID, 1u);
    lfxSgVertexUV1.xy = effectUV1;
    lfxSgUV1 = lfxSgVertexUV1;
#endif
#if (LFX_SG_UV4_MASK & 4) != 0
    lfxSgVertexUV2 = lfx_mesh_uv4(gl_VertexID, 2u);
    lfxSgVertexUV2.xy = effectMeshUV23.xy;
    lfxSgUV2 = lfxSgVertexUV2;
#endif
#if (LFX_SG_UV4_MASK & 8) != 0
    lfxSgVertexUV3 = lfx_mesh_uv4(gl_VertexID, 3u);
    lfxSgVertexUV3.xy = effectMeshUV23.zw;
    lfxSgUV3 = lfxSgVertexUV3;
#endif
#if (LFX_SG_UV4_MASK & 16) != 0
    lfxSgVertexUV4 = lfx_mesh_uv4(gl_VertexID, 4u);
    lfxSgVertexUV4.xy = effectMeshUV45.xy;
    lfxSgUV4 = lfxSgVertexUV4;
#endif
#if (LFX_SG_UV4_MASK & 32) != 0
    lfxSgVertexUV5 = lfx_mesh_uv4(gl_VertexID, 5u);
    lfxSgVertexUV5.xy = effectMeshUV45.zw;
    lfxSgUV5 = lfxSgVertexUV5;
#endif
#if (LFX_SG_UV4_MASK & 64) != 0
    lfxSgVertexUV6 = lfx_mesh_uv4(gl_VertexID, 6u);
    lfxSgVertexUV6.xy = effectMeshUV67.xy;
    lfxSgUV6 = lfxSgVertexUV6;
#endif
#if (LFX_SG_UV4_MASK & 128) != 0
    lfxSgVertexUV7 = lfx_mesh_uv4(gl_VertexID, 7u);
    lfxSgVertexUV7.xy = effectMeshUV67.zw;
    lfxSgUV7 = lfxSgVertexUV7;
#endif
#if LFX_EXTRA_MATERIAL
#if LFX_GPU_NIAGARA
    effectCustom1 = MaterialExtra1;
    effectCustom2 = MaterialExtra2;
    effectCustom3 = MaterialExtra3;
#else
    int extra = (LFX_MOTION_INSTANCE_ID - ExtraInfo.x) * ExtraInfo.y;
    if (Velocity.w > 0.5) {
        effectCustom1 = lfx_mesh_extra(extra + 4);
        effectCustom2 = vec4(0.0);
        effectCustom3 = vec4(0.0);
    } else {
        effectCustom1 = lfx_mesh_extra(extra);
        effectCustom2 = lfx_mesh_extra(extra + 4);
        effectCustom3 = lfx_mesh_extra(extra + 8);
    }
#endif
#endif
#if LFX_VERTEX_MATERIAL
    effectPositionNoOffsets = position;
#if LFX_MATERIAL_INSTANCE_TRANSFORMS
    lfxVertexInstanceLinear = particle.linear;
    lfxVertexInstanceCenter = particle.position;
    lfxVertexInstanceInverseScale = particle.inverseScale;
#endif
#if LFX_MATERIAL_BASIS
    mat3 materialBasis = lfx_mesh_tangent_basis(vertex, particle.rotation);
#if !LFX_GPU_NIAGARA
    if (Velocity.w > 0.5)
        materialBasis = lfx_unity_mesh_tangent_basis(vertex, particle.linear, particle.rotation, particle.determinantSign);
#endif
    lfxVertexMaterialNormal = materialBasis[2];
    lfxVertexMaterialTangent = materialBasis[0];
    lfxVertexMaterialBinormal = materialBasis[1];
    lfxVertexMaterialBasisDeterminant = effectUnmirrored;
#endif
#if LFX_PRESKIN_POSITION
    lfxVertexPreSkinnedPosition = vertex.position;
#endif
#if LFX_PRESKIN_NORMAL
    lfxVertexPreSkinnedNormal = vertex.normal;
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
#ifdef LFX_CSM_PARTICLE_MESH
    float normalDotLight = abs(dot(particle.linear[2].xzy, CsmCasterLightDirection.xyz));
    gl_Position = lfx_csm_vertex_clip(gl_Position, normalDotLight);
#endif
#if LFX_PARTICLE_MOTION
    vec3 previousPosition = (particle.linear * vertex.position + particle.position) + MotionCameraDelta.xyz;
    vec3 previousParticleCenter = particle.position + MotionCameraDelta.xyz;
#if LFX_MATERIAL_INSTANCE_TRANSFORMS
    mat3 previousInstanceLinear = particle.linear;
#endif
    int motionMode = int(MeshFacingAxis.w);
#if LFX_OPAQUE_MOTION
#if LFX_GPU_NIAGARA
    if (ClockScale.w < 0.5) motionMode = 0;
#else
    if (ExtraInfo.z == 0) motionMode = 0;
#endif
#endif
    if (motionMode != 0) {
#if LFX_MESH_SCENE
        LfxMeshTransform oldTransform = lfx_mesh_scene_transform(sceneInstance, true);
        previousPosition = oldTransform.linear * vertex.position + oldTransform.position + MotionCameraDelta.xyz;
        previousParticleCenter = oldTransform.position + MotionCameraDelta.xyz;
#if LFX_MATERIAL_INSTANCE_TRANSFORMS
        previousInstanceLinear = oldTransform.linear;
#endif
#else
        vec3 oldCenter;
        vec4 oldOrientation, oldScaleCamera, oldVelocity, oldOwnerPositionScale, oldOwnerRotation;
#if LFX_GPU_NIAGARA
        oldCenter = PreviousCenter;
        oldOrientation = PreviousOrientation;
        oldScaleCamera = PreviousScaleCamera;
        oldVelocity = PreviousVelocity;
        oldOwnerPositionScale = PreviousOwnerPositionScale;
        oldOwnerRotation = PreviousOwnerRotation;
#else
        int base = (LFX_MOTION_INSTANCE_ID - ExtraInfo.x) * ExtraInfo.y + 12;
        oldCenter = lfx_mesh_extra(base).xyz;
        oldOrientation = lfx_mesh_extra(base + 4);
        oldScaleCamera = lfx_mesh_extra(base + 8);
        oldVelocity = lfx_mesh_extra(base + 12);
        oldOwnerPositionScale = lfx_mesh_extra(base + 16);
        oldOwnerRotation = lfx_mesh_extra(base + 20);
#endif
        LfxMeshTransformInput oldInput = inputData;
#if LFX_GPU_NIAGARA
        oldInput.primitiveScale = PreviousOwnerScale.xzy;
        oldInput.primitiveLinear = mat3(PreviousOwnerRelative0.xyz, PreviousOwnerRelative1.xyz, PreviousOwnerRelative2.xyz);
#else
        oldInput.primitiveScale = vec3(oldOwnerPositionScale.w, componentScale.zw);
        oldInput.primitiveLinear = lfx_component_linear(oldOwnerRotation, oldInput.primitiveScale);
#endif
        oldInput.primitiveRotation = mat3(oldInput.primitiveLinear[0] * primitiveInverseScale.x,
                oldInput.primitiveLinear[1] * primitiveInverseScale.y, oldInput.primitiveLinear[2] * primitiveInverseScale.z);
        oldInput.primitivePosition = oldOwnerPositionScale.xyz + MotionCameraDelta.xyz;
        oldInput.worldOffset = MotionCameraDelta.xyz;
        LfxMeshSrt oldSrt = currentSrt;
        if (motionMode == 1) {
            oldInput.position = oldCenter;
            oldInput.orientation = oldOrientation;
            oldInput.scale = oldScaleCamera.xyz;
            oldInput.velocity = oldVelocity.xyz;
            oldInput.cameraOffset = oldScaleCamera.w;
            oldInput.cameraForward = (-cross(MotionPreviousRight.xyz, MotionPreviousUp.xyz)).xzy;
            oldInput.cameraUp = MotionPreviousUp.xzy;
            oldSrt = lfx_mesh_srt(oldInput);
        } else oldSrt.translation -= Velocity.xyz * MotionClock.x;
        LfxMeshTransform oldTransform = lfx_mesh_transform(oldSrt, oldInput);
        previousPosition = oldTransform.linear * vertex.position + oldTransform.position;
        previousParticleCenter = oldTransform.position;
#if LFX_MATERIAL_INSTANCE_TRANSFORMS
        previousInstanceLinear = oldTransform.linear;
#endif
#endif
    }
    vec3 previousRenderPosition = previousPosition.xzy * 0.01;
#if LFX_VERTEX_MATERIAL
#if LFX_MATERIAL_INSTANCE_TRANSFORMS
    lfxVertexPreviousInstanceLinear = previousInstanceLinear;
    lfxVertexPreviousInstanceCenter = previousParticleCenter;
#endif
    previousRenderPosition += lfx_material_vertex_previous(previousRenderPosition, previousParticleCenter);
#if LFX_MATERIAL_FIRST_PERSON_ROOT
    previousRenderPosition = lfx_material_first_person_previous(previousRenderPosition);
#endif
#endif
    lfxMotionShader_currentClip = gl_Position;
    lfxMotionShader_previousClip = MotionPreviousViewProjection * vec4(previousRenderPosition, 1.0);
    lfxMotionShader_validity = MotionCameraDelta.w;
#endif
#if LFX_TRANSLUCENT_MOTION_LOG
    lfxMotionShader_instanceId = uint(gl_InstanceID);
#endif
}
