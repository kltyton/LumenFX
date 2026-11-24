#version 330
#ifdef LFX_DIRECTIONAL_SHADOW_CASTER
#define LFX_CSM_CASTER_VERTEX
#moj_import <lumenfx:lighting/directional_shadow_caster.glsl>
#endif
layout(std140) uniform PointShadowFace { mat4 ShadowFaceClip; };
layout(std140) uniform PointShadowSection { vec4 ShadowSectionOffset; };
in vec3 Position;
in vec4 Color;
in vec2 UV0;
out vec2 shadowUV;
out float shadowAlpha;
void main() {
    vec3 sourcePosition = Position.xzy * 100.0 + ShadowSectionOffset.xyz;
    gl_Position = ShadowFaceClip * vec4(sourcePosition, 1.0);
#ifdef LFX_DIRECTIONAL_SHADOW_CASTER
    gl_Position = lfx_csm_vertex_clip(gl_Position);
#endif
    shadowUV = UV0;
    shadowAlpha = Color.a;
}
