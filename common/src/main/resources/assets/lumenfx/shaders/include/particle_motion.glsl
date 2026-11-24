#if LFX_PARTICLE_MOTION
#moj_import <lumenfx:particle_motion_view.glsl>
out vec4 lfxMotionShader_currentClip;
out vec4 lfxMotionShader_previousClip;
out float lfxMotionShader_validity;
#endif
#if LFX_TRANSLUCENT_MOTION_LOG
flat out uint lfxMotionShader_instanceId;
#endif
#if LFX_TRANSLUCENT_STEP_INSTANCE_BASE
layout(std140) uniform LfxMotionStepBase {
    ivec4 LfxMotionStepBaseInfo; // x = instanceBase / firstIndex, y = indirectOffsetUints, z = indirectGuard (0/1)
};
#define LFX_MOTION_INSTANCE_ID (gl_InstanceID + LfxMotionStepBaseInfo.x)
#else
#define LFX_MOTION_INSTANCE_ID gl_InstanceID
#endif
#if LFX_TRANSLUCENT_STEP_INDIRECT_GUARD
uniform usamplerBuffer LfxMotionStepIndirect;
#endif
