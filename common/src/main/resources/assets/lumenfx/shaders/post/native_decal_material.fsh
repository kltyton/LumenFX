#version 330
#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:decal_view.glsl>
#moj_import <lumenfx:scene_surface.glsl>
uniform sampler2D LinearScene;
uniform sampler2D EncodedScene;
uniform sampler2D SurfaceAlbedo;
#ifndef LFX_DECAL_SCREEN_AO
uniform sampler2D SurfaceNormalAmbient;
uniform sampler2D DBufferNormal;
#endif
uniform sampler2D SurfaceGeometry;
uniform sampler2D MaterialBase;
uniform sampler2D MaterialZero;
uniform sampler2D MaterialUnit;
uniform sampler2D DBufferColor;
#ifdef LFX_DECAL_PARTICLE_PROJECTION
uniform sampler2D DecalProjectionGeometry;
#endif
#ifdef LFX_DECAL_SCREEN_AO
uniform sampler2D DecalScreenOcclusion;
#endif
layout(location = 0) out vec4 fragColor;
#ifndef LFX_DECAL_SCREEN_AO
layout(location = 1) out vec4 receivedAlbedo;
layout(location = 2) out vec4 receivedNormalAmbient;
#endif

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(LinearScene, pixel, 0);
    vec4 base = texelFetch(MaterialBase, pixel, 0);
    vec4 albedo = texelFetch(SurfaceAlbedo, pixel, 0);
#ifndef LFX_DECAL_SCREEN_AO
    receivedAlbedo = albedo;
    receivedNormalAmbient = texelFetch(SurfaceNormalAmbient, pixel, 0);
#endif
    fragColor = scene;
#ifdef LFX_DECAL_PARTICLE_PROJECTION
    if (lfx_surface_is_particle_decal_depth(texelFetch(DecalProjectionGeometry, pixel, 0))) return;
#endif
    if (base.a == 0.0) return;
    vec4 geometry = texelFetch(SurfaceGeometry, pixel, 0);
    if (!lfx_surface_receives_decals(geometry)) return;
    int responseMask = lfx_surface_decal_response(geometry);

    vec4 decal = texelFetch(DBufferColor, pixel, 0);
    bool colorChanged = (responseMask & 1) != 0 && (decal.a != 1.0 || any(notEqual(decal.rgb, vec3(0.0))));
    vec3 material = colorChanged ? linearToSrgb(lfx_decal_base_color(srgbToLinear(base.rgb), decal)) : base.rgb;
#ifndef LFX_DECAL_SCREEN_AO
    if (colorChanged) receivedAlbedo.rgb += (material - base.rgb) * (base.a - 1.0);
    if ((responseMask & 2) != 0)
        receivedNormalAmbient.rgb = lfx_decal_view_normal(receivedNormalAmbient.rgb, texelFetch(DBufferNormal, pixel, 0));
#endif
    vec3 response = texelFetch(MaterialUnit, pixel, 0).rgb - texelFetch(MaterialZero, pixel, 0).rgb;
    vec3 encoded = texelFetch(EncodedScene, pixel, 0).rgb + (material - base.rgb) * response;
#ifdef LFX_DECAL_SCREEN_AO
    float screenOcclusion = lfx_decal_screen_occlusion(1.0, texelFetch(DecalScreenOcclusion, pixel, 0).r);
    if (screenOcclusion != 1.0 && albedo.a != 0.0) {
        vec3 occluded = linearToSrgb(srgbToLinear(material) * screenOcclusion);
        vec3 shaded = encoded + (occluded - material) * response;
        fragColor.rgb += srgbToLinear(shaded) - srgbToLinear(encoded);
    }
#else
    if (colorChanged) fragColor = vec4(srgbToLinear(encoded), scene.a);
#endif
}
