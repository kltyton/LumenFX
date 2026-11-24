#version 330
#define LFX_VERTEX_STAGE 1
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

in vec3 Center;
in vec4 Orientation;
in vec4 ScaleCamera;
in vec4 Velocity;
in vec4 Color;
in vec4 MaterialData;
in vec4 ParticleData;
in vec4 OwnerPositionScale;
in vec4 MeshOwnerRotation;
#moj_import <lumenfx:niagara_mesh_main.glsl>
