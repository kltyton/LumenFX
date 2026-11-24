#ifndef LFX_NIAGARA_VOLUME_MATERIAL
#define LFX_NIAGARA_VOLUME_MATERIAL

#if defined(LFX_VOLUME_COMPUTE) && !LFX_VOLUME_PRESHADE_WRITE
#define LFX_VOLUME_DIRECTIONAL_SHADOW 1
#endif
#ifndef LFX_VOLUME_DIRECTIONAL_SHADOW
#define LFX_VOLUME_DIRECTIONAL_SHADOW 0
#endif

layout(std140) uniform NiagaraVolumeView {
    mat4 VolumeClipToView;
    mat4 VolumeViewToWorld;
    mat4 VolumeWorldToLocal;
    mat4 VolumeLocalToWorld;
    vec4 VolumeBoundsMin;
    vec4 VolumeBoundsMax;
    vec4 VolumeResolution;
    vec4 VolumeFactors;
    vec4 VolumeTrace;
    vec4 VolumeViewport;
    vec4 VolumePass;
    vec4 VolumeLightPosition;
    vec4 VolumeLightColor;
    vec4 VolumeLightShape;
    vec4 VolumeLightTangent;
    vec4 VolumeLightingGrid;
    vec4 VolumeLightDirectionAndCone;
    vec4 VolumeLightConeFalloff;
    vec4 VolumeRectShape;
    vec4 VolumeLightKind;
    vec4 VolumeObjectTile;
    vec4 VolumeObjectOffset;
    vec4 VolumeObjectExtentRadius;
    vec4 VolumeObjectOrientation;
    mat4 VolumeMaterialLocalToWorld;
    mat4 VolumeMaterialWorldToLocal;
    vec4 VolumeActorTile;
    vec4 VolumeActorOffset;
    vec4 VolumeCustomPrimitiveData[9];
};
uniform sampler2D VolumeSceneDepth;
uniform sampler2D VolumeBlueNoiseSampler;

#if LFX_VOLUME_SURFACE_SHADOW
layout(std140) uniform VolumePointShadow {
    mat4 VolumeShadowProjection[6];
    vec4 VolumeShadowParameters;
    vec4 VolumeShadowStrength;
};
#if LFX_VOLUME_SPOT_SHADOW
uniform sampler2D VolumeSpotShadowDepth;
#moj_import <lumenfx:lighting/spot_light_shadow.glsl>
#else
uniform samplerCubeShadow VolumeShadowDepth;
#define LFX_POINT_SHADOW_QUALITY 2
#moj_import <lumenfx:lighting/point_light_shadow.glsl>
#endif
#endif

#if LFX_VOLUME_DIRECTIONAL_SHADOW
#define LFX_DIRECTIONAL_CASCADE_LIMIT 20
#moj_import <lumenfx:lighting/forward_directional_shadow.glsl>
#endif

#ifdef LFX_VOLUME_COMPUTE
#if LFX_VOLUME_PRESHADE_WRITE
layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;
layout(r11f_g11f_b10f) uniform writeonly image3D VolumeBakeExtinction;
layout(r11f_g11f_b10f) uniform writeonly image3D VolumeBakeEmission;
layout(r11f_g11f_b10f) uniform writeonly image3D VolumeBakeAlbedo;
uniform sampler2D VolumeBakeNoiseSampler;
#define gl_FragCoord vec4(0.0)
#elif LFX_VOLUME_CACHE_WRITE
layout(local_size_x = 4, local_size_y = 4, local_size_z = 4) in;
layout(r11f_g11f_b10f) uniform image3D VolumeCacheOutput;
#define gl_FragCoord vec4(0.0)
#else
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(rgba16f) uniform writeonly image2D VolumeResult;
#define gl_FragCoord vec4(vec2(gl_GlobalInvocationID.xy) + 0.5, 0.0, 1.0)
#endif
#if LFX_VOLUME_CACHE_READ || LFX_VOLUME_CACHE_TRANS_READ
uniform sampler3D VolumeLightingCache;
#endif
vec4 lfx_volume_texture(sampler2D image, vec2 uv, float bias) { return textureLod(image, uv, bias); }
vec4 lfx_volume_texture(sampler3D image, vec3 uv, float bias) { return textureLod(image, uv, bias); }
vec4 lfx_volume_texture(sampler2DArray image, vec3 uvw, float bias) { return textureLod(image, uvw, bias); }
vec4 lfx_volume_texture(samplerCube image, vec3 direction, float bias) { return textureLod(image, direction, bias); }
vec4 lfx_volume_texture(samplerCubeArray image, vec4 directionAndLayer, float bias) { return textureLod(image, directionAndLayer, bias); }
#else
vec4 lfx_volume_texture(sampler2D image, vec2 uv, float bias) { return texture(image, uv, bias); }
#endif

vec3 effectPosition;
vec3 effectParticleCenter;
vec2 effectUV = vec2(0.0);
vec2 effectUV1 = vec2(0.0);
vec4 effectColor = vec4(1.0);
vec4 effectParticle = vec4(0.0);
vec4 effectCustom = vec4(0.0);
vec4 effectCustom1 = vec4(0.0);
vec4 effectCustom2 = vec4(0.0);
vec4 effectCustom3 = vec4(0.0);
float effectDynamicValid = 0.0;
vec3 lfxVolumeLocalPosition;
vec3 lfxVolumeCoordinates;

struct LfxVolumeMaterial {
    vec3 extinction;
    vec3 emission;
    vec3 albedo;
};

void lfx_set_volume_sample(vec3 localPosition, vec3 translatedPosition) {
    lfxVolumeLocalPosition = localPosition;
    lfxVolumeCoordinates = (localPosition - VolumeBoundsMin.xyz) / (VolumeBoundsMax.xyz - VolumeBoundsMin.xyz);
    effectPosition = translatedPosition.xzy * 0.01;
    effectParticleCenter = (VolumeLocalToWorld * vec4((VolumeBoundsMin.xyz + VolumeBoundsMax.xyz) * 0.5, 1.0)).xzy * 0.01;
}

vec2 lfx_depth_viewport_uv() {
    return gl_FragCoord.xy * VolumeViewport.xy;
}

vec2 lfx_volume_ndc(vec2 uv) {
    return (uv * 2.0 - 1.0) * vec2(1.0, VolumeViewport.w);
}

vec2 lfx_volume_viewport_uv() {
    return lfx_volume_ndc(lfx_depth_viewport_uv()) * vec2(0.5, -0.5) + 0.5;
}

vec4 lfx_volume_homogeneous_view(vec2 uv, float depth) {
    float z = VolumeViewport.z > 0.5 ? depth : depth * 2.0 - 1.0;
    return VolumeClipToView * vec4(lfx_volume_ndc(uv), z, 1.0);
}

float lfx_volume_material_pixel_depth() {
    if (VolumePass.y <= 0.5) return 0.0;
    vec4 viewPosition = lfx_volume_homogeneous_view(vec2(0.0), 0.0);
    return -100.0 * viewPosition.z / viewPosition.w;
}

float lfx_scene_depth_at(vec2 uv) {
    vec4 hit = lfx_volume_homogeneous_view(uv, texture(VolumeSceneDepth, uv).r);
    return abs(hit.w) > 1.e-8 ? abs(hit.z / hit.w) * 100.0 : VolumeTrace.x;
}

float lfx_volume_material_scene_depth_at(vec2 viewportUV) {
    vec2 halfTexel = VolumeViewport.xy * 0.5;
    vec2 hostUV = clamp(viewportUV, halfTexel, 1.0 - halfTexel);
#if LFX_VOLUME_OPENGL
    hostUV.y = 1.0 - hostUV.y;
#endif
    return lfx_scene_depth_at(hostUV);
}

float lfx_scene_depth() { return lfx_scene_depth_at(lfx_depth_viewport_uv()); }
float lfx_pixel_depth() { return length(effectPosition) * 100.0; }
vec3 lfx_camera_vector() { return vec3(0.0); }

#endif
