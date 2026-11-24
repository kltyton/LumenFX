#version 330
#ifdef LFX_SPOT_SHADOW_CASTER
#moj_import <lumenfx:lighting/spot_shadow_caster.glsl>
#endif
#ifdef LFX_DIRECTIONAL_SHADOW_CASTER
#moj_import <lumenfx:lighting/directional_shadow_caster.glsl>
#endif
uniform sampler2D ShadowBlockAtlas;
in vec2 shadowUV;
in float shadowAlpha;
void main() {
#ifdef LFX_SPOT_SHADOW_CASTER
    float casterBias = lfx_spot_caster_bias();
#elif defined(LFX_DIRECTIONAL_SHADOW_CASTER)
    float casterBias = lfx_csm_geometry_bias();
#endif
#ifdef ALPHA_CUTOUT
    if (texture(ShadowBlockAtlas, shadowUV).a * shadowAlpha < ALPHA_CUTOUT) discard;
#endif
#ifdef LFX_SPOT_SHADOW_CASTER
    gl_FragDepth = lfx_spot_caster_depth(gl_FragCoord.z, casterBias);
#elif defined(LFX_DIRECTIONAL_SHADOW_CASTER)
    gl_FragDepth = clamp(gl_FragCoord.z - casterBias, 0.0, 1.0);
#endif
}
