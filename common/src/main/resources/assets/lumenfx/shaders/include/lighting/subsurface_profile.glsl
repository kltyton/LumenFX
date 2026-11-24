uniform sampler2D LfxSubsurfaceProfiles;
const int LFX_PER_PIXEL_DIFFUSION = 256;

int lfx_subsurface_profile_id(vec4 customData) {
    return int(customData.r * 255.0 + 0.5);
}

vec4 lfx_subsurface_profile_load(int profile, int column) {
    return texelFetch(LfxSubsurfaceProfiles, ivec2(column, profile), 0);
}

vec3 lfx_subsurface_profile_transmission(int profile, float thickness, vec3 normal, vec3 view, vec3 light) {
    vec4 parameters = lfx_subsurface_profile_load(profile, 3);
    vec2 extent = vec2(textureSize(LfxSubsurfaceProfiles, 0));
    vec2 uv = vec2(34.5 + thickness * (31.0 / 5.0), float(profile) + 0.5) / extent;
    vec3 transmittance = textureLod(LfxSubsurfaceProfiles, uv, 0.0).rgb;
    float anisotropy = parameters.z * 2.0 - 1.0;
    vec3 refracted = refract(view, -normal, parameters.w);
    float phaseDenominator = 1.0 - anisotropy * dot(-light, refracted);
    float phase = 0.5 * (1.0 - anisotropy * anisotropy) / max(1e-5, phaseDenominator * phaseDenominator);
    return transmittance * phase;
}
