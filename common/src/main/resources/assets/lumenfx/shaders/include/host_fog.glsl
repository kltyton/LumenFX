#moj_import <minecraft:fog.glsl>

// Host view fog represented as inscattering RGB and transmission A in the current target's space.
vec4 lfx_host_fog(vec3 cameraRelativePosition) {
    float amount = total_fog_value(
        fog_spherical_distance(cameraRelativePosition), fog_cylindrical_distance(cameraRelativePosition),
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd) * FogColor.a;
    vec3 encoded = max(FogColor.rgb, vec3(0.0));
#if LFX_LINEAR_TARGET
    vec3 linear = mix(pow((encoded + 0.055) / 1.055, vec3(2.4)), encoded / 12.92,
        step(encoded, vec3(0.04045)));
    return vec4(linear * amount, 1.0 - amount);
#else
    return vec4(encoded * amount, 1.0 - amount);
#endif
}
