#if LFX_PARTICLE_POINT_SHADOW
layout(std140) uniform ParticlePointShadowClip {
    mat4 PointShadowWorldToClip;
};
#endif

vec4 lfx_particle_raster_position(vec3 position) {
#if LFX_PARTICLE_POINT_SHADOW
    return PointShadowWorldToClip * vec4(position, 1.0);
#else
    return ProjMat * ModelViewMat * vec4(position, 1.0);
#endif
}
