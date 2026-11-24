#version 330
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:lighting/point_light_shadow.glsl>
#ifdef LFX_SPOT_SHADOW
#define LFX_SPOT_SHADOW_QUALITY LFX_POINT_SHADOW_QUALITY
#moj_import <lumenfx:lighting/spot_light_shadow.glsl>
#endif
#ifdef LFX_SHADOW_SUBSURFACE_TRANSMISSION
#moj_import <lumenfx:lighting/point_light_transmission.glsl>
#ifdef LFX_SPOT_SHADOW
#moj_import <lumenfx:lighting/spot_light_transmission.glsl>
#endif
#endif
#ifdef LFX_SHADOW_ADAPTIVE_NORMAL
#moj_import <lumenfx:substrate/adaptive_header.glsl>
#endif
#ifdef LFX_SHADOW_TOON_TRANSMISSION
#moj_import <lumenfx:substrate/toon_profile.glsl>
uniform sampler2D ShadowToonData;
#endif
#ifdef LFX_SHADOW_DECALS
#moj_import <lumenfx:decal_view.glsl>
uniform sampler2D ShadowDecalNormal;
#endif
layout(std140) uniform PointShadowView {
    mat4 ShadowCubeViewProjection[6];
    mat4 ShadowViewToSourceWorld;
    mat4 ShadowSurfaceDepthTransform;
    vec4 ShadowLightPositionAndInvRadius;
    vec4 ShadowBiasAndSharpen;
    vec4 ShadowFadeResolution;
    vec4 SpotProjectionSettings;
};
#ifdef LFX_SPOT_SHADOW
uniform sampler2D PointShadowDepth;
#else
uniform samplerCubeShadow PointShadowDepth;
#endif
uniform sampler2D ShadowSurface;
uniform sampler2D ShadowGeometry;
uniform sampler2D ShadowNormal;
uniform sampler2D SceneDepth;
out vec4 fragColor;

vec3 lfx_shadow_octahedral(vec2 encoded) {
    vec3 result = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
    if (result.z < 0.0) result.xy = (1.0 - abs(result.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), result.xy));
    return normalize(result);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 surface = texelFetch(ShadowSurface, pixel, 0);
    vec4 geometry = texelFetch(ShadowGeometry, pixel, 0);
#ifdef LFX_SHADOW_HOST_MATERIAL
    float depth = surface.a;
    if (depth < 0.0 || depth != texelFetch(SceneDepth, pixel, 0).r) discard;
    float logicalZ = 1.0 - 2.0 * depth;
    float viewZ = (ShadowSurfaceDepthTransform[2].z * logicalZ + ShadowSurfaceDepthTransform[3].z)
            / (ShadowSurfaceDepthTransform[2].w * logicalZ + ShadowSurfaceDepthTransform[3].w);
    vec3 position = vec3(geometry.xy, viewZ);
    vec3 normal = lfx_shadow_octahedral(geometry.zw);
#else
    if (surface.a == 0.0 || geometry.w != texelFetch(SceneDepth, pixel, 0).r) discard;
    vec3 position = lfx_surface_view_position(geometry, ShadowSurfaceDepthTransform, LFX_SHADOW_ZERO_TO_ONE != 0);
    vec3 normal = texelFetch(ShadowNormal, pixel, 0).xyz;
#ifdef LFX_SHADOW_DECALS
    if (lfx_surface_receives_decals(geometry) && (lfx_surface_decal_response(geometry) & 2) != 0)
        normal = lfx_decal_view_normal(normal, texelFetch(ShadowDecalNormal, pixel, 0));
#endif
#endif
    vec3 sourcePosition = (ShadowViewToSourceWorld * vec4(position, 1.0)).xyz;
    vec3 worldNormal = normalize(mat3(ShadowViewToSourceWorld) * normal);
#ifdef LFX_SHADOW_ADAPTIVE_NORMAL
    if (lfx_surface_particle_shading_model(geometry) == LFX_SHADING_MODEL_SUBSTRATE) {
        uvec4 header = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
        if (header.x != 0u) worldNormal = lfx_adaptive_top_normal(header);
    }
#endif
    vec3 toLight = ShadowLightPositionAndInvRadius.xyz - sourcePosition;
    float noL = dot(toLight, toLight) > 0.0 ? clamp(dot(worldNormal, normalize(toLight)), 0.0, 1.0) : 0.0;
#ifdef LFX_SPOT_SHADOW
    float shadow = lfx_spot_shadow_pcf(PointShadowDepth, ShadowCubeViewProjection[0], -toLight,
            ShadowFadeResolution.zw, ShadowFadeResolution.y,
            SpotProjectionSettings.y * mix(SpotProjectionSettings.w, 1.0, noL));
#else
    float slopeBias = (1.0 - noL) * ShadowBiasAndSharpen.y;
    float shadow = lfx_point_shadow_pcf(PointShadowDepth, ShadowCubeViewProjection, ShadowFadeResolution.y,
            sourcePosition, ShadowLightPositionAndInvRadius.xyz, ShadowLightPositionAndInvRadius.w,
            ShadowBiasAndSharpen.x, slopeBias);
#endif
    float encoded = lfx_point_shadow_attenuation(shadow, ShadowBiasAndSharpen.w, ShadowFadeResolution.x);
    float transmission = encoded;
#ifdef LFX_SHADOW_TOON_TRANSMISSION
    if (lfx_surface_particle_shading_model(geometry) == LFX_SHADING_MODEL_SUBSTRATE_TOON) {
        int profile = lfx_toon_profile_layer(texelFetch(ShadowToonData, pixel, 0));
        float extinction = lfx_toon_settings(profile, 2).x;
        float transmittance = lfx_spot_shadow_pcf(PointShadowDepth, ShadowCubeViewProjection[0], -toLight,
                ShadowFadeResolution.zw, ShadowFadeResolution.y, extinction * SpotProjectionSettings.z,
                true, SpotProjectionSettings.x);
        transmission = sqrt(mix(1.0, transmittance, ShadowFadeResolution.x));
    }
#endif
#ifdef LFX_SHADOW_SUBSURFACE_TRANSMISSION
    int shadingModel = lfx_surface_particle_shading_model(geometry);
    vec2 opticalParameters = vec2(0.0);
    bool usesDiffusion = false;
#ifdef LFX_SHADOW_ADAPTIVE_TRANSMISSION
    if (shadingModel == LFX_SHADING_MODEL_SUBSTRATE) {
        uvec4 diffusionHeader = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
        LfxSlabDiffusionPixel diffusion = lfx_adaptive_diffusion_pixel(pixel, diffusionHeader);
        if (diffusion.type == 2) {
            vec2 nativeParameters = lfx_subsurface_profile_load(lfx_adaptive_profile_id(diffusion.profileData), 3).xy;
            opticalParameters = vec2(nativeParameters.x * 100.0, nativeParameters.y);
        } else if (diffusion.type == 1) {
            float mfp = dot(diffusion.meanFreePath, vec3(1.0 / 3.0));
            opticalParameters = vec2(1.0 / max(1e-5, mfp), 0.08);
        }
        usesDiffusion = diffusion.type != 0;
    }
#endif
#ifdef LFX_SHADOW_LEGACY_TRANSMISSION
    if (shadingModel == LFX_SHADING_MODEL_SUBSURFACE_PROFILE) {
        int profile = lfx_subsurface_profile_id(texelFetch(ShadowSubsurfaceData, pixel, 0));
        vec2 nativeParameters = lfx_subsurface_profile_load(profile, 3).xy;
        opticalParameters = vec2(nativeParameters.x * 100.0, nativeParameters.y);
        usesDiffusion = true;
    }
#endif
    if (usesDiffusion) {
#ifdef LFX_SPOT_SHADOW
        transmission = sqrt(mix(1.0, lfx_spot_subsurface_optical_mask(PointShadowDepth, ShadowCubeViewProjection[0],
                toLight, worldNormal, opticalParameters.x * .01, opticalParameters.y, ShadowFadeResolution.zw,
                ShadowFadeResolution.y, SpotProjectionSettings.z, SpotProjectionSettings.x), ShadowFadeResolution.x));
#else
        transmission = sqrt(lfx_point_subsurface_optical_mask(toLight, worldNormal, opticalParameters.x, opticalParameters.y,
                ShadowFadeResolution.zw, ShadowFadeResolution.y, ShadowLightPositionAndInvRadius.w, ShadowCubeViewProjection));
#endif
    }
#endif
    fragColor = vec4(1.0, 1.0, encoded, transmission);
}
