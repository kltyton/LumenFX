#version 400
#ifdef LFX_CSM_PARTICLE_RIBBON
#define LFX_CSM_CASTER_VERTEX
#moj_import <lumenfx:lighting/directional_shadow_caster.glsl>
#endif
#define LFX_VERTEX_STAGE 1
#define LFX_GPU_NIAGARA 1
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <lumenfx:lighting/translucency_volume_material.glsl>
#moj_import <lumenfx:lighting/particle_point_shadow.glsl>
#moj_import <lumenfx:particle_motion.glsl>
#moj_import <lumenfx:niagara_component_transform.glsl>
#if LFX_APPLY_FOG && !LFX_PIXEL_FOG
#moj_import <lumenfx:scene_fog.glsl>
out vec4 effectFog;
#endif

layout(std140) uniform NiagaraRibbonData {
    uvec4 Attribute0;
    uvec4 Attribute1;
    uvec4 MaterialAttributes;
    uvec4 Packing0;
    uvec4 Packing1;
    uvec4 UVAttributes0;
    uvec4 UVAttributes1;
    uvec4 Flags;
    vec4 WorldOffset;
    vec4 OwnerOffset;
    vec4 OwnerRotation;
    vec4 Tint;
    vec4 UVVertical;
    vec4 ClockScale;
    uvec4 PreviousAttributes;
    vec4 PreviousOwnerPositionScale;
    vec4 PreviousOwnerRotation;
    vec4 MaterialBasisConfig;
    vec4 OwnerScale;
    vec4 PreviousOwnerScale;
    vec4 OwnerRelative0;
    vec4 OwnerRelative1;
    vec4 OwnerRelative2;
    vec4 OwnerRelative3;
    vec4 OwnerInverseRelative0;
    vec4 OwnerInverseRelative1;
    vec4 OwnerInverseRelative2;
    vec4 OwnerInverseRelative3;
    vec4 PreviousOwnerRelative0;
    vec4 PreviousOwnerRelative1;
    vec4 PreviousOwnerRelative2;
    vec4 PreviousOwnerRelative3;
    vec4 OwnerPositionHigh;
    vec4 OwnerInverseScale;
    vec4 SimulationTile;
    vec4 ComponentPreViewTile;
    vec4 ComponentPreViewOffset;
    vec4 OwnerWorldToPrevious0;
    vec4 OwnerWorldToPrevious1;
    vec4 OwnerWorldToPrevious2;
    vec4 OwnerWorldToPrevious3;
};
#moj_import <lumenfx:niagara_component_world.glsl>
uniform samplerBuffer NiagaraFloatData;
uniform usamplerBuffer NiagaraHalfData;
uniform usamplerBuffer NiagaraRibbonSorted;
uniform usamplerBuffer NiagaraRibbonGroups;
uniform usamplerBuffer NiagaraRibbonPacked;
uniform samplerBuffer NiagaraRibbonTangents;
uniform usamplerBuffer NiagaraRibbonIndirect;
uniform samplerBuffer NiagaraRibbonSlice;

out vec2 effectUV;
out vec2 effectUV1;
out vec4 effectColor;
out vec4 effectParticle;
out vec3 effectParticleCenter;
out vec4 effectCustom;
out float effectDynamicValid;
#if LFX_EXTRA_MATERIAL
out vec4 effectCustom1;
out vec4 effectCustom2;
out vec4 effectCustom3;
#endif
out vec3 effectPosition;
out vec4 effectRibbonVelocity;
out vec2 effectRibbonSize;

float lfx_half_to_float(uint value) {
    uint signBit = (value & 0x8000u) << 16;
    uint exponent = (value >> 10) & 31u;
    uint mantissa = value & 1023u;
    if (exponent == 0u) {
        if (mantissa == 0u) return uintBitsToFloat(signBit);
        int normalizedExponent = -14;
        while ((mantissa & 1024u) == 0u) { mantissa <<= 1; normalizedExponent--; }
        return uintBitsToFloat(signBit | (uint(normalizedExponent + 127) << 23) | ((mantissa & 1023u) << 13));
    }
    if (exponent == 31u) return uintBitsToFloat(signBit | 0x7f800000u | (mantissa << 13));
    return uintBitsToFloat(signBit | ((exponent + 112u) << 23) | (mantissa << 13));
}

float lfx_read(uint binding, int component, uint particle, float fallback) {
    if (binding == 0xffffffffu) return fallback;
    uint index = ((binding & 0x7fffffffu) + uint(component)) * Packing0.x + particle;
    if ((binding & 0x80000000u) == 0u) return texelFetch(NiagaraFloatData, int(index)).r;
    uint word = texelFetch(NiagaraHalfData, int(index >> 1)).r;
    return lfx_half_to_float((word >> ((index & 1u) * 16u)) & 0xffffu);
}

vec4 lfx_attribute(uint binding, uint particle, vec4 fallback) {
    return vec4(lfx_read(binding, 0, particle, fallback.x), lfx_read(binding, 1, particle, fallback.y),
                lfx_read(binding, 2, particle, fallback.z), lfx_read(binding, 3, particle, fallback.w));
}

vec3 lfx_vector(uint binding, uint particle, vec3 fallback) {
    return vec3(lfx_read(binding, 0, particle, fallback.x), lfx_read(binding, 1, particle, fallback.y),
                lfx_read(binding, 2, particle, fallback.z));
}

vec3 lfx_rotate(vec3 value, vec4 rotation) {
    vec3 twiceCross = 2.0 * cross(rotation.xyz, value);
    return value + rotation.w * twiceCross + cross(rotation.xyz, twiceCross);
}

vec3 lfx_normalize(vec3 value) {
    float squaredLength = dot(value, value);
    return squaredLength > 0.0 ? value * inversesqrt(squaredLength) : vec3(0.0);
}

float lfx_packed(uint index) { return uintBitsToFloat(texelFetch(NiagaraRibbonPacked, int(index)).r); }
float lfx_tangent(uint index, int component) { return texelFetch(NiagaraRibbonTangents, int(index * 4u) + component).r; }
float lfx_slice(uint index, int component) { return texelFetch(NiagaraRibbonSlice, int(index * 5u) + component).r; }

float lfx_uv_u(uint mode, uint overrideBinding, uint distanceBinding, uint p0, uint p1, float alpha,
               uint raw, uint first, float distance, float distributionScale) {
    if (overrideBinding != 0xffffffffu)
        return mix(lfx_read(overrideBinding, 0, p0, 0.0), lfx_read(overrideBinding, 0, p1, 0.0), alpha);
    if (mode == 0u) return (float(raw - first) + alpha) * distributionScale;
    if (mode == 1u || mode == 2u) return distance * distributionScale;
    if (mode == 3u && distanceBinding != 0xffffffffu)
        return mix(lfx_read(distanceBinding, 0, p0, 0.0), lfx_read(distanceBinding, 0, p1, 0.0), alpha) * distributionScale;
    return 0.0;
}

float lfx_uv_v(uint binding, uint p0, uint p1, float alpha, float v) {
    if (binding == 0xffffffffu) return v;
    vec2 a = vec2(lfx_read(binding, 0, p0, 0.0), lfx_read(binding, 1, p0, 1.0));
    vec2 b = vec2(lfx_read(binding, 0, p1, 0.0), lfx_read(binding, 1, p1, 1.0));
    vec2 range = mix(a, b, alpha);
    return (1.0 - v * range.x) + v * range.y;
}

#moj_import <lumenfx:material_sg_uv4.glsl>
#moj_import <lumenfx:material_vertex.glsl>
void main() {
#if LFX_TRANSLUCENT_STEP_INSTANCE_BASE
    if (LfxMotionStepBaseInfo.z != 0 && uint(LfxMotionStepBaseInfo.x) >= texelFetch(NiagaraRibbonIndirect, LfxMotionStepBaseInfo.y).r) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
#endif
    uint encodedVertexId = uint(gl_VertexID);
    uint raw0 = (encodedVertexId >> Packing0.y) & Packing0.z;
    uint interp = (encodedVertexId >> Packing0.w) & Packing1.x;
    uint slice = encodedVertexId & Packing1.y;
    uint group = Packing1.z != 0u ? texelFetch(NiagaraRibbonGroups, int(raw0)).r : 0u;
    uint groupBase = group * 8u;
    uint first = texelFetch(NiagaraRibbonPacked, int(groupBase + 6u)).r;
    uint last = texelFetch(NiagaraRibbonPacked, int(groupBase + 7u)).r;
    uint raw1 = min(raw0 + 1u, last);
    uint p0 = texelFetch(NiagaraRibbonSorted, int(raw0)).r;
    uint p1 = texelFetch(NiagaraRibbonSorted, int(raw1)).r;
    float alpha = float(interp) * uintBitsToFloat(texelFetch(NiagaraRibbonIndirect, 16).r);
    vec3 a = lfx_vector(Attribute0.x, p0, vec3(0.0));
    vec3 b = lfx_vector(Attribute0.x, p1, vec3(0.0));
    vec3 t0 = vec3(lfx_tangent(raw0, 0), lfx_tangent(raw0, 1), lfx_tangent(raw0, 2));
    vec3 t1 = vec3(lfx_tangent(raw1, 0), lfx_tangent(raw1, 1), lfx_tangent(raw1, 2));
    float d0 = lfx_tangent(raw0, 3), d1 = lfx_tangent(raw1, 3);
    float a2 = alpha * alpha, a3 = a2 * alpha;
    vec3 sourceCenter = (2.0*a3 - 3.0*a2 + 1.0)*a + (d1-d0)*(a3 - 2.0*a2 + alpha)*t0
                      + (d1-d0)*(a3-a2)*t1 + (-2.0*a3 + 3.0*a2)*b;
    LfxComponentWorldPosition worldCenter = lfx_component_world_position(sourceCenter, Flags.y != 0u, false);
    vec3 center = lfx_component_translated_position(worldCenter);
    vec3 direction = mix(t0, t1, alpha);
    if (Flags.y != 0u) {
        mat3 primitive = mat3(OwnerRelative0.xyz, OwnerRelative1.xyz, OwnerRelative2.xyz);
        direction = primitive * (direction * OwnerInverseScale.xyz);
    }
    direction = lfx_normalize(direction);
    effectParticleCenter = center;
    vec3 facing;
    if (Attribute1.x == 0xffffffffu) facing = lfx_normalize(-center);
    else {
        facing = mix(lfx_vector(Attribute1.x, p0, vec3(0.0)), lfx_vector(Attribute1.x, p1, vec3(0.0)), alpha);
        if (Flags.y != 0u) {
            mat3 primitive = mat3(OwnerRelative0.xyz, OwnerRelative1.xyz, OwnerRelative2.xyz);
            facing = primitive * (facing * OwnerInverseScale.xyz);
        }
        facing = lfx_normalize(facing);
    }
    vec3 right = lfx_normalize(cross(facing, direction));
    if (Packing1.w == 2u) { vec3 previous = right; right = -facing; facing = previous; }
    vec3 rotatedRight = right, materialFacing = facing;
    if (Attribute0.w != 0xffffffffu) {
        float twist = mix(lfx_read(Attribute0.w, 0, p0, 0.0), lfx_read(Attribute0.w, 0, p1, 0.0), alpha);
        float sine = sin(twist), cosine = cos(twist);
        rotatedRight = cosine * right + sine * facing;
        materialFacing = -sine * right + cosine * facing;
    }
    vec3 geoUp = cross(direction, rotatedRight);
    float materialWidth = mix(lfx_read(Attribute0.z, 0, p0, 1.0), lfx_read(Attribute0.z, 0, p1, 1.0), alpha);
    vec3 materialVelocity = mix(lfx_vector(Attribute1.w, p0, vec3(0.0)),
                                lfx_vector(Attribute1.w, p1, vec3(0.0)), alpha);
    if (Flags.y != 0u) {
        mat3 primitive = mat3(OwnerRelative0.xyz, OwnerRelative1.xyz, OwnerRelative2.xyz);
        materialVelocity = primitive * (materialVelocity * OwnerInverseScale.xyz);
    }
    float velocitySquared = dot(materialVelocity, materialVelocity);
    float inverseVelocityLength = velocitySquared > 0.0 ? inversesqrt(velocitySquared) : 0.0;
    vec3 velocityDirection = velocitySquared > 0.0 ? materialVelocity * inverseVelocityLength : vec3(0.0);
    float velocityLength = velocitySquared > 0.0 ? 1.0 / inverseVelocityLength : 0.0;
    effectRibbonVelocity = vec4(velocityDirection, velocityLength);
    effectRibbonSize = vec2(materialWidth);
    vec3 geometryOffset = (rotatedRight * lfx_slice(slice, 0) + geoUp * lfx_slice(slice, 1)) * materialWidth;
    LfxComponentWorldPosition worldPosition = worldCenter;
    worldPosition.Offset += geometryOffset;
    vec3 position = lfx_component_translated_position(worldPosition).xzy * .01;
    float distance = mix(d0, d1, alpha);
    float v = lfx_slice(slice, 4);
    float u0 = lfx_uv_u(UVAttributes1.z, UVAttributes0.x, UVAttributes1.x, p0, p1, alpha, raw0, first, distance, lfx_packed(groupBase + 2u));
    float u1 = lfx_uv_u(UVAttributes1.w, UVAttributes0.z, UVAttributes1.x, p0, p1, alpha, raw0, first, distance, lfx_packed(groupBase + 5u));
    effectUV = vec2(u0 * lfx_packed(groupBase) + lfx_packed(groupBase + 1u),
                    lfx_uv_v(UVAttributes0.y, p0, p1, alpha, v) * UVVertical.x + UVVertical.y);
    effectUV1 = vec2(u1 * lfx_packed(groupBase + 3u) + lfx_packed(groupBase + 4u),
                     lfx_uv_v(UVAttributes0.w, p0, p1, alpha, v) * UVVertical.z + UVVertical.w);
    effectColor = mix(lfx_attribute(Attribute0.y, p0, vec4(1.0)), lfx_attribute(Attribute0.y, p1, vec4(1.0)), alpha) * Tint;
    float age = mix(lfx_read(Attribute1.y, 0, p0, 0.0), lfx_read(Attribute1.y, 0, p1, 0.0), alpha);
    float randomValue = mix(lfx_read(Attribute1.z, 0, p0, 0.0), lfx_read(Attribute1.z, 0, p1, 0.0), alpha);
    float lifetime = mix(lfx_read(Flags.w, 0, p0, 0.0), lfx_read(Flags.w, 0, p1, 0.0), alpha);
    float particleAge = mix(lfx_read(Flags.z, 0, p0, 0.0), lfx_read(Flags.z, 0, p1, 0.0), alpha);
    effectParticle = vec4(age, randomValue, lifetime, ClockScale.x - particleAge);
    effectCustom = mix(lfx_attribute(MaterialAttributes.x, p0, vec4(1.0)), lfx_attribute(MaterialAttributes.x, p1, vec4(1.0)), alpha);
    effectDynamicValid = float(Flags.x);
#if LFX_EXTRA_MATERIAL
    effectCustom1 = mix(lfx_attribute(MaterialAttributes.y, p0, vec4(1.0)), lfx_attribute(MaterialAttributes.y, p1, vec4(1.0)), alpha);
    effectCustom2 = mix(lfx_attribute(MaterialAttributes.z, p0, vec4(1.0)), lfx_attribute(MaterialAttributes.z, p1, vec4(1.0)), alpha);
    effectCustom3 = mix(lfx_attribute(MaterialAttributes.w, p0, vec4(1.0)), lfx_attribute(MaterialAttributes.w, p1, vec4(1.0)), alpha);
#endif
    vec3 positionNoOffsets = position;
    lfx_sg_assign_uv4_pair(effectUV, effectUV1);
#if LFX_VERTEX_MATERIAL
    effectPositionNoOffsets = position;
#if LFX_MATERIAL_BASIS
    lfx_ribbon_material_basis(center.xzy * .01, direction.xzy, rotatedRight.xzy, materialFacing.xzy, vec2(lfx_slice(slice, 2), lfx_slice(slice, 3)),
            MaterialBasisConfig.x > 0.5, MaterialBasisConfig.y > 0.5, lfxVertexMaterialNormal, lfxVertexMaterialTangent);
    lfxVertexMaterialBinormal = cross(lfxVertexMaterialNormal, lfxVertexMaterialTangent);
    lfxVertexMaterialBasisDeterminant = 1.0;
#endif
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
    vec3 shadowNormal = lfx_ribbon_world_normal(direction.xzy, rotatedRight.xzy, materialFacing.xzy,
            vec2(lfx_slice(slice, 2), lfx_slice(slice, 3)), MaterialBasisConfig.x > 0.5);
    gl_Position = lfx_csm_vertex_clip(gl_Position, abs(dot(shadowNormal, CsmCasterLightDirection.xyz)));
#endif
#if LFX_PARTICLE_MOTION
    vec3 previousPosition = positionNoOffsets;
    int motionMode = int(ClockScale.z);
#if LFX_OPAQUE_MOTION
    if (ClockScale.w < 0.5) motionMode = 0;
#endif
    if (motionMode == 1) {
        vec3 oldA = lfx_vector(PreviousAttributes.x, p0, vec3(0.0));
        vec3 oldB = lfx_vector(PreviousAttributes.x, p1, vec3(0.0));
        vec3 oldSourceCenter = (2.0*a3 - 3.0*a2 + 1.0)*oldA + (d1-d0)*(a3 - 2.0*a2 + alpha)*t0
                        + (d1-d0)*(a3-a2)*t1 + (-2.0*a3 + 3.0*a2)*oldB;
        LfxComponentWorldPosition oldWorldCenter = lfx_component_world_position(oldSourceCenter, Flags.y != 0u, true);
        vec3 oldCenter = lfx_component_translated_position(oldWorldCenter);
        vec3 oldDirection = mix(t0, t1, alpha);
        if (Flags.y != 0u) {
            mat3 primitive = mat3(PreviousOwnerRelative0.xyz, PreviousOwnerRelative1.xyz, PreviousOwnerRelative2.xyz);
            oldDirection = primitive * (oldDirection * lfx_component_ribbon_previous_inverse_scale(primitive));
        }
        oldDirection = lfx_normalize(oldDirection);
        vec3 oldFacing;
        if (PreviousAttributes.y == 0xffffffffu) oldFacing = lfx_normalize(-oldCenter);
        else {
            oldFacing = mix(lfx_vector(PreviousAttributes.y, p0, vec3(0.0)), lfx_vector(PreviousAttributes.y, p1, vec3(0.0)), alpha);
            if (Flags.y != 0u) {
                mat3 primitive = mat3(PreviousOwnerRelative0.xyz, PreviousOwnerRelative1.xyz, PreviousOwnerRelative2.xyz);
                oldFacing = primitive * (oldFacing * lfx_component_ribbon_previous_inverse_scale(primitive));
            }
            oldFacing = lfx_normalize(oldFacing);
        }
        vec3 oldRight = lfx_normalize(cross(oldFacing, oldDirection));
        if (Packing1.w == 2u) { vec3 saved = oldRight; oldRight = -oldFacing; oldFacing = saved; }
        vec3 oldRotatedRight = oldRight;
        if (PreviousAttributes.z != 0xffffffffu) {
            float oldTwist = mix(lfx_read(PreviousAttributes.z, 0, p0, 0.0), lfx_read(PreviousAttributes.z, 0, p1, 0.0), alpha);
            oldRotatedRight = cos(oldTwist) * oldRight + sin(oldTwist) * oldFacing;
        }
        vec3 oldGeoUp = cross(oldDirection, oldRotatedRight);
        float oldWidth = mix(lfx_read(PreviousAttributes.w, 0, p0, 1.0), lfx_read(PreviousAttributes.w, 0, p1, 1.0), alpha);
        oldWorldCenter.Offset += (oldRotatedRight * lfx_slice(slice, 0) + oldGeoUp * lfx_slice(slice, 1)) * oldWidth;
        previousPosition = lfx_component_translated_position(oldWorldCenter).xzy * .01;
    } else if (motionMode == 2) {
        mat3 primitive = mat3(OwnerRelative0.xyz, OwnerRelative1.xyz, OwnerRelative2.xyz);
        vec3 offsetVelocity = -velocityDirection * (velocityLength * MotionClock.x);
        if (Flags.y != 0u) offsetVelocity = primitive * (offsetVelocity * OwnerInverseScale.xyz);
        LfxComponentWorldPosition oldWorldPosition = worldCenter;
        oldWorldPosition.Offset += offsetVelocity;
        oldWorldPosition.Offset += geometryOffset;
        previousPosition = lfx_component_translated_position(oldWorldPosition).xzy * .01;
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
