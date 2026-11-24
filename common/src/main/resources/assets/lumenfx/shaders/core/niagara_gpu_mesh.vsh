#version 330
#define LFX_VERTEX_STAGE 1
#define LFX_GPU_NIAGARA 1
#define LFX_MESH_NIAGARA 1
#define LFX_MESH_PARTICLE 1
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <lumenfx:niagara_mesh_transform.glsl>
#moj_import <lumenfx:niagara_mesh_geometry.glsl>
#moj_import <lumenfx:particle_motion.glsl>
#if LFX_APPLY_FOG && !LFX_PIXEL_FOG
#moj_import <lumenfx:scene_fog.glsl>
out vec4 effectFog;
#endif

#moj_import <lumenfx:niagara_gpu_particle_data.glsl>
#moj_import <lumenfx:niagara_mesh_scene.glsl>
vec3 Center;
vec4 Orientation;
vec4 ScaleCamera;
vec4 Velocity;
vec4 Color;
vec4 MaterialData;
vec4 ParticleData;
vec4 OwnerPositionScale;
vec4 MeshOwnerRotation;
uint MeshParticleIndex;

#if LFX_EXTRA_MATERIAL
vec4 MaterialExtra1, MaterialExtra2, MaterialExtra3;
#endif
vec3 PreviousCenter;
vec4 PreviousOrientation, PreviousScaleCamera, PreviousVelocity, PreviousOwnerPositionScale, PreviousOwnerRotation;

void lfx_load_mesh_particle() {
#if LFX_MESH_SCENE_CULLING
    int sceneAddress = MeshSceneInfo.y + LFX_MOTION_INSTANCE_ID;
    uint particle = uint(texelFetch(NiagaraSortedIndices, sceneAddress >> 2)[sceneAddress & 3]);
#else
    uint particle = Flags.y != 0u ? uint(texelFetch(NiagaraSortedIndices, int(Flags.w) + LFX_MOTION_INSTANCE_ID).r) : uint(LFX_MOTION_INSTANCE_ID);
#endif
    MeshParticleIndex = particle;
    Center = vec3(lfx_particle_float(Attribute0.x, 0, particle, HostPosition.x),
                  lfx_particle_float(Attribute0.x, 1, particle, HostPosition.y),
                  lfx_particle_float(Attribute0.x, 2, particle, HostPosition.z));
    if (Flags.z == 0u) {
        if (Attribute0.x == 0xffffffffu && HostPosition.w == 0.0) Center = OwnerOffset.xzy * 100.0;
        else Center += WorldOffset.xzy * 100.0;
    }
    MeshOwnerRotation = vec4(-OwnerRotation.x, -OwnerRotation.z, -OwnerRotation.y, OwnerRotation.w);
    OwnerPositionScale = vec4(OwnerOffset.xzy * 100.0, ClockScale.y);
    Orientation = vec4(lfx_particle_float(MeshAttributes.y, 0, particle, HostMeshOrientation.x),
                       lfx_particle_float(MeshAttributes.y, 1, particle, HostMeshOrientation.y),
                       lfx_particle_float(MeshAttributes.y, 2, particle, HostMeshOrientation.z),
                       lfx_particle_float(MeshAttributes.y, 3, particle, HostMeshOrientation.w));
    ScaleCamera = vec4(lfx_particle_float(MeshAttributes.x, 0, particle, HostMeshScale.x),
                       lfx_particle_float(MeshAttributes.x, 1, particle, HostMeshScale.y),
                       lfx_particle_float(MeshAttributes.x, 2, particle, HostMeshScale.z),
                       lfx_particle_float(AxisAttributes.w, 0, particle, HostOffsetPivot.x));
    Velocity = vec4(lfx_particle_float(AxisAttributes.x, 0, particle, HostVelocity.x),
                    lfx_particle_float(AxisAttributes.x, 1, particle, HostVelocity.y),
                    lfx_particle_float(AxisAttributes.x, 2, particle, HostVelocity.z), 0.0);
    Color = vec4(lfx_particle_float(Attribute0.w, 0, particle, HostColor.x),
                 lfx_particle_float(Attribute0.w, 1, particle, HostColor.y),
                 lfx_particle_float(Attribute0.w, 2, particle, HostColor.z),
                 lfx_particle_float(Attribute0.w, 3, particle, HostColor.w)) * Tint;
    ParticleData = vec4(lfx_particle_float(Attribute1.x, 0, particle, HostAgeRandom.x),
                        lfx_particle_float(Attribute1.y, 0, particle, HostAgeRandom.y),
                        lfx_particle_float(Attribute2.x, 0, particle, Atlas.z), float(Flags.x));
    MaterialData = vec4(lfx_particle_float(Attribute2.z, 0, particle, HostDynamic.x),
                        lfx_particle_float(Attribute2.z, 1, particle, HostDynamic.y),
                        lfx_particle_float(Attribute2.z, 2, particle, HostDynamic.z),
                        lfx_particle_float(Attribute2.z, 3, particle, HostDynamic.w));
    PreviousCenter = vec3(lfx_particle_float(MeshPreviousAttributes.x, 0, particle, HostPreviousPosition.x),
                           lfx_particle_float(MeshPreviousAttributes.x, 1, particle, HostPreviousPosition.y),
                           lfx_particle_float(MeshPreviousAttributes.x, 2, particle, HostPreviousPosition.z));
    if (Flags.z == 0u) PreviousCenter = MeshPreviousAttributes.x == 0xffffffffu && HostPreviousPosition.w == 0.0
            ? OwnerOffset.xzy * 100.0 : PreviousCenter + WorldOffset.xzy * 100.0;
    PreviousScaleCamera = vec4(lfx_particle_float(MeshPreviousAttributes.y, 0, particle, HostPreviousScaleOffset.x),
                              lfx_particle_float(MeshPreviousAttributes.y, 1, particle, HostPreviousScaleOffset.y),
                              lfx_particle_float(MeshPreviousAttributes.y, 2, particle, HostPreviousScaleOffset.z),
                              lfx_particle_float(MeshPreviousAxis.x, 0, particle, HostPreviousScaleOffset.w));
    PreviousOrientation = vec4(lfx_particle_float(MeshPreviousAttributes.z, 0, particle, HostPreviousOrientation.x),
                              lfx_particle_float(MeshPreviousAttributes.z, 1, particle, HostPreviousOrientation.y),
                              lfx_particle_float(MeshPreviousAttributes.z, 2, particle, HostPreviousOrientation.z),
                              lfx_particle_float(MeshPreviousAttributes.z, 3, particle, HostPreviousOrientation.w));
    PreviousVelocity = vec4(lfx_particle_float(MeshPreviousAttributes.w, 0, particle, HostPreviousVelocity.x),
                           lfx_particle_float(MeshPreviousAttributes.w, 1, particle, HostPreviousVelocity.y),
                           lfx_particle_float(MeshPreviousAttributes.w, 2, particle, HostPreviousVelocity.z), 0.0);
    PreviousOwnerPositionScale = MeshPreviousOwnerPositionScale;
    PreviousOwnerRotation = MeshPreviousOwnerRotation;
#if LFX_EXTRA_MATERIAL
    MaterialExtra1 = lfx_extra_material(ExtraAttributes.x, particle, HostExtra1);
    MaterialExtra2 = lfx_extra_material(ExtraAttributes.y, particle, HostExtra2);
    MaterialExtra3 = lfx_extra_material(ExtraAttributes.z, particle, HostExtra3);
#endif
}

#moj_import <lumenfx:niagara_mesh_main.glsl>
