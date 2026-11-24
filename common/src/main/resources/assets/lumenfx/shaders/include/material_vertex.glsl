#moj_import <lumenfx:material_vertex_basis.glsl>
#if LFX_VERTEX_MATERIAL
out vec3 effectPositionNoOffsets;
vec3 lfx_material_vertex_current(vec3 position, vec3 particleCenter);
#if LFX_PARTICLE_MOTION
vec3 lfx_material_vertex_previous(vec3 position, vec3 particleCenter);
#endif
#endif
