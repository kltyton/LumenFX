#ifndef LFX_SCENE_SURFACE_INCLUDED
#define LFX_SCENE_SURFACE_INCLUDED
// Native geometry stores view XY, integer reception/contact flags, and physical device depth.
const int LFX_DEFAULT_LIT_PARTICLE_FLAG = 1024;
const int LFX_UNLIT_PARTICLE_FLAG = 32768;
const int LFX_ANISOTROPIC_PARTICLE_FLAG = 65536;
const int LFX_PARTICLE_LIGHT_CHANNEL_SHIFT = 11;
const int LFX_PARTICLE_SHADING_MODEL_SHIFT = 17;
const int LFX_PARTICLE_FIRST_PERSON_FLAG = 2097152;
const int LFX_PARTICLE_UNITY_COAT_FLAG = 4194304;
const int LFX_PARTICLE_URP_LIT_FLAG = 8388608;
const int LFX_SHADING_MODEL_SUBSURFACE = 2;
const int LFX_SHADING_MODEL_PREINTEGRATED_SKIN = 3;
const int LFX_SHADING_MODEL_CLEAR_COAT = 4;
const int LFX_SHADING_MODEL_SUBSURFACE_PROFILE = 5;
const int LFX_SHADING_MODEL_TWOSIDED_FOLIAGE = 6;
const int LFX_SHADING_MODEL_HAIR = 7;
const int LFX_SHADING_MODEL_CLOTH = 8;
const int LFX_SHADING_MODEL_EYE = 9;
const int LFX_SHADING_MODEL_SUBSTRATE = 12;
const int LFX_SHADING_MODEL_SUBSTRATE_HAIR = 13;
const int LFX_SHADING_MODEL_SUBSTRATE_EYE = 14;
const int LFX_SHADING_MODEL_SUBSTRATE_TOON = 15;
int lfx_surface_particle_shading_model(vec4 geometry) { return (int(geometry.z) >> LFX_PARTICLE_SHADING_MODEL_SHIFT) & 15; }
bool lfx_surface_uses_unity_coat(vec4 geometry) { return (int(geometry.z) & LFX_PARTICLE_UNITY_COAT_FLAG) != 0; }
bool lfx_surface_uses_urp_lit(vec4 geometry) { return (int(geometry.z) & LFX_PARTICLE_URP_LIT_FLAG) != 0; }
bool lfx_surface_uses_subsurface_profile(int model) {
    return model == LFX_SHADING_MODEL_SUBSURFACE_PROFILE || model == LFX_SHADING_MODEL_EYE || model == LFX_SHADING_MODEL_SUBSTRATE_EYE;
}
bool lfx_surface_has_default_lit_particle(vec4 geometry) { return (int(geometry.z) & LFX_DEFAULT_LIT_PARTICLE_FLAG) != 0; }
bool lfx_surface_has_anisotropic_particle(vec4 geometry) { return (int(geometry.z) & LFX_ANISOTROPIC_PARTICLE_FLAG) != 0; }
int lfx_surface_light_channels(vec4 geometry) {
    return lfx_surface_has_default_lit_particle(geometry) ? (int(geometry.z) >> LFX_PARTICLE_LIGHT_CHANNEL_SHIFT) & 7 : 1;
}
bool lfx_surface_casts_contact_shadow(vec4 geometry) { return (int(geometry.z) & 128) != 0; }
bool lfx_surface_receives_decals(vec4 geometry) { return (int(geometry.z) & 8) != 0; }
bool lfx_surface_is_particle_decal_depth(vec4 geometry) { return (int(geometry.z) & 16384) != 0; }
int lfx_surface_decal_response(vec4 geometry) { return int(geometry.z) & 7; }

vec3 lfx_surface_view_position(vec4 geometry, mat4 projection, bool zeroToOne) {
    float ndcDepth = zeroToOne ? geometry.w : geometry.w * 2.0 - 1.0;
    vec2 numeratorXY = vec2(projection[0].z - ndcDepth * projection[0].w,
                            projection[1].z - ndcDepth * projection[1].w);
    float numerator = dot(numeratorXY, geometry.xy) + projection[3].z - ndcDepth * projection[3].w;
    float denominator = ndcDepth * projection[2].w - projection[2].z;
    return vec3(geometry.xy, numerator / denominator);
}
#endif
