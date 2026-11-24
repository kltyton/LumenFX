#version 330

#moj_import <lumenfx:srgb.glsl>
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:lighting/default_lit_brdf.glsl>
#moj_import <lumenfx:lighting/unity_clear_coat.glsl>
#if LFX_DIRECTIONAL_PARTICLE_SHADOWS
#moj_import <lumenfx:lighting/forward_directional_shadow.glsl>
#endif

layout(std140) uniform LfxDirectionalParticleView {
    mat4 DirectionalProjection;
    mat4 DirectionalViewToWorld;
    vec4 DirectionalLightDirectionRadius;
    vec4 DirectionalLightColorSoftRadius;
    vec4 DirectionalLightScalesChannels;
};
uniform sampler2D DirectionalParticleAlbedo;
uniform sampler2D DirectionalParticleNormal;
uniform sampler2D DirectionalParticleGeometry;
uniform sampler2D DirectionalParticleSpecular;
uniform sampler2D DirectionalSceneDepth;
#if LFX_DIRECTIONAL_PARTICLE_CUSTOM
uniform sampler2D DirectionalParticleCustomData;
#endif
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 geometry = texelFetch(DirectionalParticleGeometry, pixel, 0);
    int channels = int(DirectionalLightScalesChannels.z);
    int shadingModel = lfx_surface_particle_shading_model(geometry);
    bool unityCoat = false;
#if LFX_DIRECTIONAL_PARTICLE_CUSTOM
    unityCoat = lfx_surface_uses_unity_coat(geometry);
#endif
    if (!lfx_surface_has_default_lit_particle(geometry)
            || (shadingModel != 1 && !unityCoat)
            || (lfx_surface_light_channels(geometry) & channels) == 0
            || geometry.w != texelFetch(DirectionalSceneDepth, pixel, 0).r) discard;

    vec3 viewPosition = lfx_surface_view_position(geometry, DirectionalProjection, LFX_DIRECTIONAL_PARTICLE_ZERO_TO_ONE != 0);
    mat3 world = mat3(DirectionalViewToWorld);
    vec3 normal = normalize(world * texelFetch(DirectionalParticleNormal, pixel, 0).xyz).xzy;
    vec3 view = normalize(world * -viewPosition).xzy;
    vec3 direction = DirectionalLightDirectionRadius.xyz;
    float noL = max(dot(normal, direction), 0.0);
    if (noL == 0.0) discard;

    float visibility = 1.0;
#if LFX_DIRECTIONAL_PARTICLE_SHADOWS
    vec3 position = (DirectionalViewToWorld * vec4(viewPosition, 1.0)).xyz.xzy * 100.0;
    visibility = lfx_forward_directional_shadow(position, -viewPosition.z * 100.0)
            * lfx_forward_directional_static(position);
#endif

    vec3 diffuse = srgbToLinear(texelFetch(DirectionalParticleAlbedo, pixel, 0).rgb);
    vec4 specular = texelFetch(DirectionalParticleSpecular, pixel, 0);
    vec2 scales = DirectionalLightScalesChannels.xy;
    vec3 response;
    if (unityCoat) {
#if LFX_DIRECTIONAL_PARTICLE_CUSTOM
        vec2 coat = texelFetch(DirectionalParticleCustomData, pixel, 0).xy;
#else
        vec2 coat = vec2(0.0);
#endif
        response = lfx_unity_coat_direct(diffuse, specular, coat, normal, view, direction, scales);
    } else if (lfx_surface_uses_urp_lit(geometry)) {
        response = lfx_unity_coat_direct(diffuse, specular, vec2(0.0), normal, view, direction, scales);
    } else {
        vec3 lobe = lfx_default_lit_specular(specular.rgb, specular.a, normal, view, direction, noL,
                DirectionalLightDirectionRadius.w, DirectionalLightColorSoftRadius.w, 1.0);
        response = diffuse * (scales.x / LFX_BRDF_PI) + lobe * scales.y;
    }
    vec3 color = DirectionalLightColorSoftRadius.rgb * (visibility * noL) * response;
#if !LFX_DIRECTIONAL_PARTICLE_LINEAR
    color = linearToSrgb(color);
#endif
    fragColor = vec4(color, 0.0);
}
