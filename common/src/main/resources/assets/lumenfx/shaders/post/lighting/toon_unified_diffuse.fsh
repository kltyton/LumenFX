#version 330

#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:substrate/toon_profile.glsl>

uniform sampler2D ToonDiffuse;
uniform sampler2D ToonRampWeight;
uniform sampler2D ToonParticleGeometry;
uniform sampler2D ToonParticleCustom;
uniform sampler2D ToonSceneDepth;
layout(location=0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 geometry = texelFetch(ToonParticleGeometry, pixel, 0);
    if (lfx_surface_particle_shading_model(geometry) != LFX_SHADING_MODEL_SUBSTRATE_TOON
            || geometry.w != texelFetch(ToonSceneDepth, pixel, 0).r) discard;
    vec4 surface = texelFetch(ToonParticleCustom, pixel, 0);
    int profile = lfx_toon_profile_layer(surface);
    vec2 weighted = texelFetch(ToonRampWeight, pixel, 0).rg;
    float position = weighted.y / max(weighted.x, 0.0001);
    position += lfx_toon_diffuse_offset(profile, lfx_toon_pattern_uv(surface), lfx_toon_settings(profile, 0));
    vec3 diffuse = texelFetch(ToonDiffuse, pixel, 0).rgb * lfx_toon_ramp(profile, 0, clamp(position, 0.0, 1.0));
    fragColor = vec4(diffuse, 0.0);
}
