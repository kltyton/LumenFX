#ifndef LFX_PARTICLE_MOTION_VIEW_GLSL
#define LFX_PARTICLE_MOTION_VIEW_GLSL
layout(std140) uniform ParticleMotionView {
    mat4 MotionPreviousViewProjection;
    vec4 MotionPreviousRight;
    vec4 MotionPreviousUp;
    vec4 MotionCameraDelta;
    vec4 MotionClock; // Current delta, previous real/game time, clock availability.
    vec4 MotionWorldTile;
    vec4 MotionPreViewTranslation;
    vec4 MotionPreviousProjection;
    mat4 MotionWorldToView;
    mat4 MotionViewToWorld;
    mat4 MotionWorldToCamera;
    mat4 MotionCameraToWorld;
    vec4 MotionFirstPersonFov;
    vec4 MotionFirstPersonScale;
    vec4 MotionFirstPersonTransform0;
    vec4 MotionFirstPersonTransform1;
    vec4 MotionFirstPersonTransform2;
};
#endif
