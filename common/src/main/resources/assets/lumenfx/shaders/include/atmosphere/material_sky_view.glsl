#moj_import <lumenfx:atmosphere/sky_view_math.glsl>
#moj_import <lumenfx:atmosphere/transmittance_math.glsl>
#moj_import <lumenfx:math/interleaved_gradient_noise.glsl>

uniform sampler2D MaterialAtmosphereSkyView;

vec3 lfx_material_atmosphere_view_luminance(vec3 direction, vec2 viewportUv) {
    if (MaterialAtmosphereSkyLuminance.w == 0.0) return vec3(0.0);
    vec3 local = vec3(dot(direction, MaterialAtmosphereSkyForward.xyz), dot(direction, MaterialAtmosphereSkyLeft.xyz),
            dot(direction, MaterialAtmosphereSkyUp.xyz));
    float height = MaterialAtmosphereSkyForward.w;
    if (MaterialAtmosphereOrthoHeight.w != 0.0)
        height = dot(MaterialAtmosphereOrthoHeight.xyz, vec3(viewportUv, 1.0));
    float radius = MaterialAtmospherePlanet.w;
    bool ground = false;
    if (dot(local, local) > 0.0) {
        vec2 intersections = lfx_atmosphere_ray_sphere_intersections(vec3(0.0, 0.0, height), local, vec3(0.0), radius);
        ground = intersections.y > 0.0;
    }
    vec2 uv = lfx_sky_view_uv(local, height, radius, ground,
            vec2(textureSize(MaterialAtmosphereSkyView, 0)));
    vec3 luminance = textureLod(MaterialAtmosphereSkyView, uv, 0.0).rgb * MaterialAtmosphereSkyLuminance.rgb;
    if (MaterialAtmosphereTopAndValid.z != 0.0) return luminance;
    vec3 error = luminance * MaterialAtmosphereSkyQuantization.rgb;
    error = uintBitsToFloat(floatBitsToUint(error) & uvec3(0xff800000u));
    float noise = lfx_interleaved_gradient_noise(viewportUv * MaterialAtmosphereSkyViewport.xy,
            MaterialAtmosphereSkyQuantization.w);
    return luminance + error * noise;
}
