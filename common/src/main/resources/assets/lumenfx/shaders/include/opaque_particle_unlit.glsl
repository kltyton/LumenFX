#ifndef LFX_OPAQUE_PARTICLE_UNLIT_INCLUDED
#define LFX_OPAQUE_PARTICLE_UNLIT_INCLUDED
#moj_import <lumenfx:scene_surface.glsl>

#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION
void lfx_write_unlit_particle_surface(float deviceDepth) {
    lfxParticleDiffuse = vec4(0.0);
    lfxParticleNormalAo = vec4(0.0);
    lfxParticlePositionDepth = vec4(0.0, 0.0, float(LFX_PARTICLE_CONTACT_SHADOW | LFX_UNLIT_PARTICLE_FLAG), deviceDepth);
    lfxParticleF0Roughness = vec4(0.0);
#if LFX_PARTICLE_GBUFFER
    lfxParticleSourceMaterial = vec4(0.0);
#endif
#if LFX_PARTICLE_ANISOTROPY_TARGET >= 0
    lfxParticleTangentAnisotropy = vec4(0.5);
#endif
#if LFX_PARTICLE_CUSTOM_DATA_TARGET >= 0
    lfxParticleCustomData = vec4(0.0);
#endif
}
#endif
#endif
