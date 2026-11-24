#moj_import <lumenfx:lighting/particle_point_shadow.glsl>
#moj_import <lumenfx:lighting/translucency_volume_material.glsl>
#ifdef LFX_CSM_PARTICLE_SPRITE
#define LFX_CSM_CASTER_VERTEX
#moj_import <lumenfx:lighting/directional_shadow_caster.glsl>
#endif

#if LFX_SPHERICAL_SPRITE_NORMALS
out vec3 effectMaterialSphereCenter;
flat out float effectMaterialSphereRadius;
#endif
const vec2 Corners[6] = vec2[6](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
);
#if LFX_SPRITE_CUTOUT
layout(std140) uniform SpriteCutoutData { ivec4 CutoutInfo; };
uniform samplerBuffer SpriteCutoutUVs;
#endif
vec3 lfx_sprite_safe_normalize(vec3 value) {
    return value / max(length(value), 0.000001);
}

vec3 lfx_sprite_camera_direction(vec3 center) {
    float coordinateScale = SubUVControl.y > 0.5 ? 100.0 : 1.0;
    return lfx_sprite_safe_normalize(-center * coordinateScale);
}

void lfx_sprite_axes(vec3 center, vec3 particleVelocity, vec3 particleFacing, vec3 particleAlignment,
                     out vec3 right, out vec3 up) {
    int facingMode = int(SpriteConfig.x);
    int alignmentMode = int(SpriteConfig.y);
    vec3 cameraDirection = lfx_sprite_camera_direction(center);
    vec3 customFacing = lfx_sprite_safe_normalize(particleFacing);
    float velocityLength = length(particleVelocity);
    vec3 velocity = velocityLength > 0.000001 ? particleVelocity / velocityLength : vec3(0.0, 1.0, 0.0);
    vec3 alignment = lfx_sprite_safe_normalize(particleAlignment);
    vec3 face = facingMode == 2 ? customFacing : cameraDirection;
    if (alignmentMode != 0) {
        vec3 direction = alignmentMode == 2 ? alignment : velocity;
        right = lfx_sprite_safe_normalize(-cross(face, direction));
        up = facingMode == 2 ? cross(face, right) : direction;
    } else if (facingMode == 1) {
        vec3 facing = cross(CameraRight.xyz, CameraUp.xyz);
        right = lfx_sprite_safe_normalize(-cross(facing, CameraUp.xyz));
        up = cross(facing, right);
    } else if (facingMode == 2) {
        right = lfx_sprite_safe_normalize(-cross(face, CameraUp.xyz));
        up = cross(face, right);
    } else if (facingMode == 3) {
        right = lfx_sprite_safe_normalize(-cross(cameraDirection, vec3(0.0, 1.0, 0.0)));
        up = cross(cameraDirection, right);
    } else if (facingMode == 4) {
        float blend = clamp(dot(center, center) * 10000.0 * SpritePivotBlend.y - SpritePivotBlend.z, 0.0, 1.0);
        right = mix(-cross(cameraDirection, vec3(0.0, 1.0, 0.0)), CameraRight.xyz, blend);
        up = mix(cross(cameraDirection, -cross(cameraDirection, vec3(0.0, 1.0, 0.0))), CameraUp.xyz, blend);
    } else {
        right = CameraRight.xyz;
        up = CameraUp.xyz;
    }
}
void lfx_sprite_billboard_uv(vec2 signedSize, out vec2 uvPosition, out vec2 uvUnflipped) {
#if LFX_SPRITE_CUTOUT
    int cutoutVertex = gl_VertexID & (CutoutInfo.x - 1);
    vec2 uvFlip = vec2(signedSize.x < 0.0 ? -1.0 : 1.0, signedSize.y < 0.0 ? -1.0 : 1.0)
            * vec2(SubUVControl.z < 0.0 ? -1.0 : 1.0, SubUVControl.w < 0.0 ? -1.0 : 1.0);
    if (uvFlip.x * uvFlip.y < 0.0) cutoutVertex = CutoutInfo.x - 1 - cutoutVertex;
    float frameCount = float(CutoutInfo.y);
    float remainder = SpriteSubImage - trunc(SpriteSubImage / frameCount) * frameCount;
    uint cutoutFrame = uint(max(0.0, trunc(remainder)));
    uvUnflipped = texelFetch(SpriteCutoutUVs, int(cutoutFrame) * CutoutInfo.x + cutoutVertex).xy;
    uvPosition = vec2(uvFlip.x < 0.0 ? 1.0 - uvUnflipped.x : uvUnflipped.x,
                      uvFlip.y < 0.0 ? 1.0 - uvUnflipped.y : uvUnflipped.y);
#else
    vec2 corner = Corners[gl_VertexID];
    uvPosition = vec2(corner.x * 0.5 + 0.5, 0.5 - corner.y * 0.5);
    uvUnflipped = uvPosition;
#endif
}

#if !LFX_GPU_NIAGARA
float lfx_unity_billboard_viewport_scale(vec3 center, vec3 horizontal, vec3 vertical, vec2 size) {
    // Measure the projection at the particle center. The viewport spans two NDC units along each axis.
    vec4 clipCenter = ProjMat * (ModelViewMat * vec4(center, 1.0));
    if (clipCenter.w <= 0.0) return 1.0;
    vec4 clipHorizontal = ProjMat * (ModelViewMat * vec4(horizontal, 0.0));
    vec4 clipVertical = ProjMat * (ModelViewMat * vec4(vertical, 0.0));
    vec2 centerNdc = clipCenter.xy / clipCenter.w;
    vec2 extent = abs(clipHorizontal.xy - centerNdc * clipHorizontal.w) * abs(size.x)
                + abs(clipVertical.xy - centerNdc * clipVertical.w) * abs(size.y);
    float fraction = max(extent.x, extent.y) / (2.0 * clipCenter.w);
    if (fraction <= 0.0) return 1.0;
    return min(max(fraction, SpritePivotBlend.y), SpritePivotBlend.z) / fraction;
}
#endif

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
    lfx_load_particle();
#endif
    vec2 uvPosition, uvUnflipped;
    lfx_sprite_billboard_uv(SizeRotation.xy, uvPosition, uvUnflipped);
    vec2 size = SizeRotation.xy;
    float pixelSizeRatio = 1.0;
    vec4 color = Color;
    int coverageMask = int(Geometry.y);
    if ((coverageMask & 16) != 0 && ProjMat[3][3] < 1.0) {
        size = abs(size);
        float depth = -(ModelViewMat * vec4(Center, 1.0)).z;
        float halfSize = max(size.x, size.y) * 0.5;
        float pixelSize = max(Geometry.w / ProjMat[0][0] * depth * 2.0, halfSize);
        pixelSizeRatio = pixelSize / halfSize;
        float coverage = clamp(halfSize * 2.0 / pixelSize, 0.0, 1.0);
        coverage *= coverage;
        vec4 blend = vec4((coverageMask & 1) != 0 ? Geometry.z : 0.0,
                         (coverageMask & 2) != 0 ? Geometry.z : 0.0,
                         (coverageMask & 4) != 0 ? Geometry.z : 0.0,
                         (coverageMask & 8) != 0 ? Geometry.z : 0.0);
        color = Color * coverage * blend + Color * (vec4(1.0) - blend);
    }
    if (SubUVControl.y > 0.5) size = abs(size);
    float rotation = int(SpriteConfig.y) == 1 ? 0.0 : SizeRotation.z;
    // Velocity alignment affects the billboard basis, while the Niagara material reads the authored rotation.
    effectSpriteRotation = SubUVControl.y > 0.5 ? -SizeRotation.z : -rotation;
    float c = cos(rotation), s = sin(rotation);
    vec3 right, up;
    lfx_sprite_axes(Center, SpriteVelocity, SpriteFacing, SpriteAlignment, right, up);
    vec3 particleCenter = Center + lfx_sprite_camera_direction(Center) * SpriteConfig.z;
#if !LFX_GPU_NIAGARA
    // The CPU Unity payload uses the otherwise inactive raw Niagara SubUV index to select its size bounds.
    if (SubUVControl.y < 0.5 && SpriteSubImage < 0.0) {
        size *= lfx_unity_billboard_viewport_scale(particleCenter, right * c + up * s, up * c - right * s, size);
    }
#endif
    vec2 point = vec2(uvPosition.x - SpriteConfig.w, SpritePivotBlend.x - uvPosition.y) * size * pixelSizeRatio;
    vec3 position = particleCenter
        + right * (point.x * c - point.y * s) + up * (point.x * s + point.y * c);
    effectParticleCenter = particleCenter.xzy * 100.0;
#if LFX_SPHERICAL_SPRITE_NORMALS
    vec2 materialSize = abs(SizeRotation.xy);
    vec3 materialCenterOffset = (right * c + up * s) * ((0.5 - SpriteConfig.w) * materialSize.x)
            + (right * s - up * c) * ((0.5 - SpritePivotBlend.x) * materialSize.y);
    effectParticleCenter += materialCenterOffset.xzy * 100.0;
#endif
    effectUV1 = uvUnflipped;
    if (SubUVControl.y > 0.5) {
        vec2 uvSign = vec2(SizeRotation.x < 0.0 ? -1.0 : 1.0,
                           SizeRotation.y < 0.0 ? -1.0 : 1.0);
        vec2 sampleUV = vec2(0.5) + (uvPosition - vec2(0.5)) * SubUVControl.zw * uvSign;
        effectUV = (AtlasUV.xy + sampleUV) * AtlasUV.zw;
        effectNextUV = (AtlasNextUV.xy + sampleUV) * AtlasNextUV.zw;
        effectUnflippedUV = (AtlasUV.xy + effectUV1) * AtlasUV.zw;
        effectNextUnflippedUV = (AtlasNextUV.xy + effectUV1) * AtlasNextUV.zw;
    } else {
        vec2 sampleUV = mix(effectUV1, vec2(1.0) - effectUV1, lessThan(SubUVControl.zw, vec2(0.0)));
        effectUV = mix(AtlasUV.xy, AtlasUV.zw, sampleUV);
        if (any(notEqual(AtlasNextUV, vec4(0.0)))) {
            effectNextUV = mix(AtlasNextUV.xy, AtlasNextUV.zw, sampleUV);
            effectNextUnflippedUV = mix(AtlasNextUV.xy, AtlasNextUV.zw, effectUV1);
        } else {
            vec2 atlasStep = AtlasUV.zw - AtlasUV.xy;
            float columns = round(1.0 / atlasStep.x);
            float column = round(AtlasUV.x / atlasStep.x);
            vec2 nextOrigin = column + 1.0 < columns ? vec2(AtlasUV.z, AtlasUV.y) : vec2(0.0, AtlasUV.w);
            effectNextUV = mix(nextOrigin, nextOrigin + atlasStep, sampleUV);
            effectNextUnflippedUV = mix(nextOrigin, nextOrigin + atlasStep, effectUV1);
        }
        effectUnflippedUV = mix(AtlasUV.xy, AtlasUV.zw, effectUV1);
    }
    effectColor = color;
    effectParticle = ParticleData;
    vec3 materialVelocity = int(SpriteConfig.y) == 2 ? lfx_sprite_safe_normalize(SpriteAlignment) : SpriteVelocity;
    float materialSpeed = length(materialVelocity);
    vec3 materialDirection = normalize(materialVelocity);
    effectSpriteVelocity = vec4(materialDirection.x, materialDirection.z, materialDirection.y, materialSpeed);
    effectSpriteSize = abs(SizeRotation.xy) * 100.0;
    effectCustom = MaterialData;
#if LFX_EXTRA_MATERIAL
#if LFX_GPU_NIAGARA
    effectCustom1 = MaterialExtra1;
    effectCustom2 = MaterialExtra2;
    effectCustom3 = MaterialExtra3;
#else
    int extraBase = ExtraInfo.x + LFX_MOTION_INSTANCE_ID * ExtraInfo.y;
    effectCustom1 = lfx_read_extra(extraBase);
    effectCustom2 = lfx_read_extra(extraBase + 4);
    effectCustom3 = lfx_read_extra(extraBase + 8);
#endif
#endif
    effectDynamicValid = DynamicValid;
    effectSubImageLerp = SubUVControl.x;
    vec3 positionNoOffsets = position;
    lfx_sg_assign_uv4_pair(effectUV, effectUV1);
#if LFX_VERTEX_MATERIAL
    effectPositionNoOffsets = position;
#if LFX_MATERIAL_BASIS
    vec2 materialUVSign = vec2(SubUVControl.z < 0.0 ? -1.0 : 1.0, SubUVControl.w < 0.0 ? -1.0 : 1.0);
    vec3 materialTangent = (right * c + up * s) * materialUVSign.x;
    vec3 materialUp = (right * s - up * c) * materialUVSign.y;
    vec3 materialNormal = -normalize(cross(materialTangent, materialUp));
    if (materialUVSign.x != materialUVSign.y) materialNormal = -materialNormal;
    lfxVertexMaterialNormal = materialNormal.xzy;
    lfxVertexMaterialTangent = materialTangent.xzy;
    lfxVertexMaterialBinormal = materialUp.xzy;
    lfxVertexMaterialBasisDeterminant = sign(dot(cross(lfxVertexMaterialTangent, lfxVertexMaterialBinormal), lfxVertexMaterialNormal));
#endif
#if LFX_PRESKIN_POSITION
    lfxVertexPreSkinnedPosition = Center.xzy * 100.0;
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
#if LFX_SPRITE_CUTOUT
    // SubUV cutout vertices can jump between frames; keep their lighting at the particle position.
    lfx_tlv_vertex(CutoutInfo.y > 1 ? Center : position);
#else
    lfx_tlv_vertex(position);
#endif
#endif
#if LFX_SPHERICAL_SPRITE_NORMALS
    effectMaterialSphereCenter = effectParticleCenter + (position - positionNoOffsets).xzy * 100.0;
    effectMaterialSphereRadius = 0.5 * min(abs(SizeRotation.x), abs(SizeRotation.y)) * 100.0;
#endif
#if LFX_APPLY_FOG && !LFX_PIXEL_FOG
    effectFog = lfx_vertex_fog(position);
#endif
    gl_Position = lfx_particle_raster_position(position);
#ifdef LFX_CSM_PARTICLE_SPRITE
    float normalDotLight = abs(dot(lfx_sprite_safe_normalize(cross(right, up)), CsmCasterLightDirection.xyz));
    gl_Position = lfx_csm_vertex_clip(gl_Position, normalDotLight);
#endif
#if LFX_PARTICLE_MOTION
    vec4 oldCenterMode, oldSizeOffset;
    vec3 oldVelocity, oldFacing, oldAlignment;
#if LFX_GPU_NIAGARA
    oldCenterMode = PreviousSpriteCenterMode;
    oldSizeOffset = PreviousSpriteSizeOffset;
    oldVelocity = PreviousSpriteVelocity;
    oldFacing = PreviousSpriteFacing;
    oldAlignment = PreviousSpriteAlignment;
#else
    int previousBase = ExtraInfo.x + LFX_MOTION_INSTANCE_ID * ExtraInfo.y + 12;
    oldCenterMode = lfx_read_extra(previousBase);
    oldSizeOffset = lfx_read_extra(previousBase + 4);
    oldVelocity = lfx_read_extra(previousBase + 8).xyz;
    oldFacing = lfx_read_extra(previousBase + 12).xyz;
    oldAlignment = lfx_read_extra(previousBase + 16).xyz;
#endif
    vec3 cameraDelta = MotionCameraDelta.xzy * 0.01;
    int motionMode = int(oldCenterMode.w);
#if LFX_OPAQUE_MOTION
#if LFX_GPU_NIAGARA
    if (ClockScale.w < 0.5) motionMode = 0;
#else
    if (ExtraInfo.z == 0) motionMode = 0;
#endif
#endif
    vec3 previousPosition = positionNoOffsets + cameraDelta;
    if (motionMode == 1) {
        vec3 oldCenter = oldCenterMode.xyz + cameraDelta;
        vec2 oldUVPosition, oldUVUnflipped;
        lfx_sprite_billboard_uv(oldSizeOffset.xy, oldUVPosition, oldUVUnflipped);
        vec2 oldPoint = vec2(oldUVPosition.x - SpriteConfig.w, SpritePivotBlend.x - oldUVPosition.y) * abs(oldSizeOffset.xy);
        float oldRotation = int(SpriteConfig.y) == 1 ? 0.0 : oldSizeOffset.z;
        float oldC = cos(oldRotation), oldS = sin(oldRotation);
        vec3 oldRight, oldUp;
        lfx_sprite_axes(oldCenter, oldVelocity, oldFacing, oldAlignment, oldRight, oldUp);
        vec3 oldParticleCenter = oldCenter + lfx_sprite_camera_direction(oldCenter) * oldSizeOffset.w;
        previousPosition = oldParticleCenter
                + oldRight * (oldPoint.x * oldC - oldPoint.y * oldS) + oldUp * (oldPoint.x * oldS + oldPoint.y * oldC);
    } else if (motionMode == 2) {
        vec3 translation = SpriteVelocity * (MotionClock.x * 0.01);
        previousPosition -= translation;
    }
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
