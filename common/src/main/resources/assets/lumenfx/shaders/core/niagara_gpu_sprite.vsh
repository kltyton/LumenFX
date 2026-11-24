#version 400
#define LFX_VERTEX_STAGE 1
#define LFX_GPU_NIAGARA 1
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
#moj_import <lumenfx:niagara_gpu_particle_data.glsl>
#moj_import <lumenfx:niagara_component_transform.glsl>
#moj_import <lumenfx:niagara_component_world.glsl>

vec3 Center;
vec3 SizeRotation;
vec4 AtlasUV;
vec4 Color;
vec4 ParticleData;
vec4 MaterialData;
vec4 AtlasNextUV;
vec4 SubUVControl;
vec3 SpriteVelocity;
vec3 SpriteFacing;
vec3 SpriteAlignment;
vec4 SpriteConfig;
vec3 SpritePivotBlend;
float SpriteSubImage;
float DynamicValid;
#if LFX_EXTRA_MATERIAL
vec4 MaterialExtra1;
vec4 MaterialExtra2;
vec4 MaterialExtra3;
#endif
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
#if LFX_EXTRA_MATERIAL
out vec4 effectCustom1;
out vec4 effectCustom2;
out vec4 effectCustom3;
#endif
out float effectSubImageLerp;
out vec3 effectPosition;
flat out vec4 effectSpriteVelocity;
flat out vec2 effectSpriteSize;

#if LFX_PARTICLE_MOTION
vec4 PreviousSpriteCenterMode;
vec4 PreviousSpriteSizeOffset;
vec3 PreviousSpriteVelocity;
vec3 PreviousSpriteFacing;
vec3 PreviousSpriteAlignment;

vec3 lfx_sprite_previous_vector(uint descriptor, vec3 fallback, uint particle) {
    vec3 value = vec3(lfx_particle_float(descriptor, 0, particle, fallback.x),
                      lfx_particle_float(descriptor, 2, particle, fallback.z),
                      lfx_particle_float(descriptor, 1, particle, fallback.y));
    mat3 previous = lfx_component_minecraft_linear(PreviousOwnerRelative0, PreviousOwnerRelative1, PreviousOwnerRelative2);
    return Flags.z != 0u ? previous * (value * OwnerInverseScale.xzy) : value;
}

void lfx_load_previous_sprite(uint particle) {
    vec3 source = vec3(lfx_particle_float(SpritePreviousAttributes.x, 0, particle, SpriteHostPreviousPosition.x),
                       lfx_particle_float(SpritePreviousAttributes.x, 1, particle, SpriteHostPreviousPosition.y),
                       lfx_particle_float(SpritePreviousAttributes.x, 2, particle, SpriteHostPreviousPosition.z));
    vec3 center = lfx_component_translated_position(lfx_component_world_position(source, Flags.z != 0u, true)).xzy * 0.01;
    PreviousSpriteCenterMode = vec4(center, float(SpritePreviousAxis.w));
    PreviousSpriteSizeOffset = vec4(lfx_particle_float(SpritePreviousAttributes.y, 0, particle, SpriteHostPreviousSizeRotation.x) * 0.01,
                                    lfx_particle_float(SpritePreviousAttributes.y, 1, particle, SpriteHostPreviousSizeRotation.y) * 0.01,
                                    -lfx_particle_float(SpritePreviousAttributes.z, 0, particle, SpriteHostPreviousSizeRotation.z) * (3.141592653589793 / 180.0),
                                    lfx_particle_float(SpritePreviousAxis.z, 0, particle, SpriteHostPreviousSizeRotation.w) * 0.01);
    PreviousSpriteVelocity = lfx_sprite_previous_vector(SpritePreviousAttributes.w, SpriteHostPreviousVelocity.xyz, particle);
    PreviousSpriteFacing = lfx_sprite_previous_vector(SpritePreviousAxis.x, SpriteHostPreviousFacing.xyz, particle);
    PreviousSpriteAlignment = lfx_sprite_previous_vector(SpritePreviousAxis.y, SpriteHostPreviousAlignment.xyz, particle);
}
#endif

void lfx_load_particle() {
    uint particle = Flags.y != 0u ? uint(texelFetch(NiagaraSortedIndices, int(Flags.w) + LFX_MOTION_INSTANCE_ID).r) : uint(LFX_MOTION_INSTANCE_ID);
    vec3 sourcePosition = vec3(lfx_particle_float(Attribute0.x, 0, particle, HostPosition.x),
                               lfx_particle_float(Attribute0.x, 1, particle, HostPosition.y),
                               lfx_particle_float(Attribute0.x, 2, particle, HostPosition.z));
    Center = lfx_component_translated_position(lfx_component_world_position(sourcePosition, Flags.z != 0u, false)).xzy * 0.01;
    SizeRotation = vec3(lfx_particle_float(Attribute0.y, 0, particle, HostSizeRotation.x) * 0.01,
                        lfx_particle_float(Attribute0.y, 1, particle, HostSizeRotation.y) * 0.01,
                        -lfx_particle_float(Attribute0.z, 0, particle, HostSizeRotation.z) * (3.141592653589793 / 180.0));
    Color = vec4(lfx_particle_float(Attribute0.w, 0, particle, HostColor.x),
                 lfx_particle_float(Attribute0.w, 1, particle, HostColor.y),
                 lfx_particle_float(Attribute0.w, 2, particle, HostColor.z),
                 lfx_particle_float(Attribute0.w, 3, particle, HostColor.w)) * Tint;
    float normalizedAge = lfx_particle_float(Attribute1.x, 0, particle, HostAgeRandom.x);
    float randomValue = lfx_particle_float(Attribute1.y, 0, particle, HostAgeRandom.y);
    float lifetime = lfx_particle_float(Attribute1.z, 0, particle, 0.0);
    float age = lfx_particle_float(Attribute1.w, 0, particle, 0.0);
    ParticleData = vec4(normalizedAge, randomValue, lifetime, ClockScale.x - age);
    MaterialData = Attribute2.z == 0xffffffffu ? HostDynamic
            : vec4(lfx_particle_float(Attribute2.z, 0, particle, 0.0),
                   lfx_particle_float(Attribute2.z, 1, particle, 0.0),
                   lfx_particle_float(Attribute2.z, 2, particle, 0.0),
                   lfx_particle_float(Attribute2.z, 3, particle, 0.0));
    DynamicValid = float(Flags.x);
    SpriteVelocity = vec3(lfx_particle_float(AxisAttributes.x, 0, particle, HostVelocity.x),
                          lfx_particle_float(AxisAttributes.x, 2, particle, HostVelocity.z),
                          lfx_particle_float(AxisAttributes.x, 1, particle, HostVelocity.y));
    SpriteFacing = vec3(lfx_particle_float(AxisAttributes.y, 0, particle, HostFacing.x),
                        lfx_particle_float(AxisAttributes.y, 2, particle, HostFacing.z),
                        lfx_particle_float(AxisAttributes.y, 1, particle, HostFacing.y));
    SpriteAlignment = vec3(lfx_particle_float(AxisAttributes.z, 0, particle, HostAlignment.x),
                           lfx_particle_float(AxisAttributes.z, 2, particle, HostAlignment.z),
                           lfx_particle_float(AxisAttributes.z, 1, particle, HostAlignment.y));
    if (Flags.z != 0u) {
        mat3 primitive = lfx_component_minecraft_linear(OwnerRelative0, OwnerRelative1, OwnerRelative2);
        vec3 inverseScale = OwnerInverseScale.xzy;
        SpriteVelocity = primitive * (SpriteVelocity * inverseScale);
        SpriteFacing = primitive * (SpriteFacing * inverseScale);
        SpriteAlignment = primitive * (SpriteAlignment * inverseScale);
    }
    SpriteConfig = vec4(float(OrientationInfo.y), float(OrientationInfo.z),
                        lfx_particle_float(AxisAttributes.w, 0, particle, HostOffsetPivot.x) * 0.01,
                        lfx_particle_float(OrientationInfo.x, 0, particle, HostOffsetPivot.y));
    SpritePivotBlend = vec3(lfx_particle_float(OrientationInfo.x, 1, particle, HostOffsetPivot.z), FacingBlend.xy);
#if LFX_EXTRA_MATERIAL
    MaterialExtra1 = lfx_extra_material(ExtraAttributes.x, particle, HostExtra1);
    MaterialExtra2 = lfx_extra_material(ExtraAttributes.y, particle, HostExtra2);
    MaterialExtra3 = lfx_extra_material(ExtraAttributes.z, particle, HostExtra3);
#endif
    float subImage = lfx_particle_float(Attribute2.x, 0, particle, Atlas.z);
    SpriteSubImage = subImage;
    float fraction = subImage - floor(subImage);
    float first = subImage - fraction + 0.5;
    float next = first + 1.0;
    vec2 reciprocal = 1.0 / Atlas.xy;
    AtlasUV = vec4(floor(first - Atlas.x * trunc(first / Atlas.x)), floor(first * reciprocal.x), reciprocal);
    AtlasNextUV = vec4(floor(next - Atlas.x * trunc(next / Atlas.x)), floor(next * reciprocal.x), reciprocal);
    vec2 uvScale = vec2(lfx_particle_float(Attribute2.y, 0, particle, HostUVScale.x),
                        lfx_particle_float(Attribute2.y, 1, particle, HostUVScale.y));
    SubUVControl = vec4(Atlas.w != 0.0 ? fraction : 0.0, 1.0, uvScale);
#if LFX_PARTICLE_MOTION
    lfx_load_previous_sprite(particle);
#endif
}

#moj_import <lumenfx:niagara_sprite_main.glsl>
