#version 330

#if LFX_MATERIAL_BASIS
vec3 lfxVertexMaterialNormal;
vec3 lfxVertexMaterialTangent;
vec3 lfxVertexMaterialBinormal;
float lfxVertexMaterialBasisDeterminant;
out vec3 lfxUnityParticleNormal;
out vec3 lfxUnityParticleTangent;
out vec3 lfxUnityParticleBitangent;
#endif

vec3 lfx_material_vertex_current(vec3 position, vec3 particleCenter) {
#if LFX_MATERIAL_BASIS
    lfxUnityParticleNormal = lfxVertexMaterialNormal.xzy;
    lfxUnityParticleTangent = lfxVertexMaterialTangent.xzy;
    lfxUnityParticleBitangent = lfxVertexMaterialBinormal.xzy;
#endif
    return vec3(0.0);
}

#if LFX_PARTICLE_MOTION
vec3 lfx_material_vertex_previous(vec3 position, vec3 particleCenter) {
    return vec3(0.0);
}
#endif
