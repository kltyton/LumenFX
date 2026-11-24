#version 330
#moj_import <lumenfx:scene_surface.glsl>
#moj_import <lumenfx:lighting/subsurface_profile.glsl>
#if LFX_SSS_ADAPTIVE
#moj_import <lumenfx:substrate/adaptive_header.glsl>
#endif
#if LFX_SSS_PASS == 0
#moj_import <lumenfx:math/interleaved_gradient_noise.glsl>
#if LFX_SSS_AFIS
uniform sampler2D LfxSubsurfaceCustom;
#endif
#else
uniform sampler2D LfxSubsurfaceCustom;
#if LFX_SSS_AFIS
uniform sampler2D LfxSubsurfaceSelection;
#endif
#endif

layout(std140) uniform LfxSubsurfaceView {
    mat4 SubsurfaceProjection;
    vec4 SubsurfaceParams;
    ivec4 SubsurfaceControls;
    mat4 SubsurfacePreviousClip;
    vec4 SubsurfaceJitter;
    ivec4 SubsurfaceBurley;
};
uniform sampler2D LfxSubsurfaceInput;
uniform sampler2D LfxSubsurfacePosition;
uniform sampler2D LfxSubsurfaceDepth;
#if LFX_SSS_PASS == 3
uniform sampler2D LfxUnfilteredDiffuse;
uniform sampler2D LfxSubsurfaceMaterial;
#endif

in vec2 texCoord;
layout(location=0) out vec4 fragColor;
#if LFX_SSS_AFIS && LFX_SSS_PASS == 0
layout(location=1) out vec4 subsurfaceSelection;
#endif
#if LFX_SSS_AFIS && LFX_SSS_PASS == 2
layout(location=1) out vec4 subsurfaceHistory;
#endif

ivec2 lfx_subsurface_pixel(vec2 uv) {
    ivec2 extent = textureSize(LfxSubsurfacePosition, 0);
    return clamp(ivec2(uv * vec2(extent)), ivec2(0), extent - 1);
}

bool lfx_subsurface_pixel_geometry(ivec2 pixel, out vec4 geometry) {
    geometry = texelFetch(LfxSubsurfacePosition, pixel, 0);
    if (geometry.w != texelFetch(LfxSubsurfaceDepth, pixel, 0).r) return false;
    int model = lfx_surface_particle_shading_model(geometry);
#if LFX_SSS_ADAPTIVE
    if (model == LFX_SHADING_MODEL_SUBSTRATE) {
        uvec4 header = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
        return header.x != 0u && (header.z & LFX_ADAPTIVE_DIFFUSION_PRESENT) != 0u;
    }
#endif
    return lfx_surface_uses_subsurface_profile(model);
}

#if LFX_SSS_PASS != 0 || LFX_SSS_AFIS
vec4 lfx_subsurface_custom_data(ivec2 pixel, vec4 geometry) {
#if LFX_SSS_ADAPTIVE
    if (lfx_surface_particle_shading_model(geometry) == LFX_SHADING_MODEL_SUBSTRATE) {
        uvec4 header = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
        LfxSlabDiffusionPixel data = lfx_adaptive_diffusion_pixel(pixel, header);
        if (data.type == 2)
            return vec4(float(lfx_adaptive_profile_id(data.profileData)) / 255.0, 0.0, 0.0, lfx_adaptive_profile_scale(data.profileData));
        return vec4(float(LFX_PER_PIXEL_DIFFUSION) / 255.0, 0.0, 0.0, 1.0);
    }
#endif
    return texelFetch(LfxSubsurfaceCustom, pixel, 0);
}

vec4 lfx_subsurface_kernel(int profile, int column) {
    if (profile == LFX_PER_PIXEL_DIFFUSION) return vec4(1.0, 1.0, 1.0, 1.0 / 1024.0);
    return lfx_subsurface_profile_load(profile, column);
}
#endif

#if LFX_SSS_AFIS && LFX_SSS_PASS != 0
bool lfx_subsurface_reference(vec2 uv, out ivec2 pixel, out vec4 geometry, out int profile) {
    ivec2 extent = textureSize(LfxSubsurfaceSelection, 0);
    ivec2 tile = clamp(ivec2(uv * vec2(extent)), ivec2(0), extent - 1);
    vec4 selection = texelFetch(LfxSubsurfaceSelection, tile, 0);
    pixel = ivec2(selection.xy);
    profile = int(selection.z) - 1;
    if (profile < 0 || !lfx_subsurface_pixel_geometry(pixel, geometry)) return false;
    if (SubsurfaceBurley.w == 0) profile = lfx_subsurface_profile_id(lfx_subsurface_custom_data(pixel, geometry));
    return true;
}
#endif

float lfx_subsurface_scene_depth(vec4 geometry) {
    vec3 position = lfx_surface_view_position(geometry, SubsurfaceProjection, SubsurfaceControls.y != 0);
    return max(abs(position.z) * 100.0, 1e-6);
}

#if LFX_SSS_AFIS && LFX_SSS_PASS != 0
#moj_import <lumenfx:lighting/burley_scattering.glsl>
#endif

#if LFX_SSS_PASS == 0
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 sum = vec4(0.0);
    float count = 0.0;
#if LFX_SSS_AFIS
    ivec2 reference = ivec2(-1);
#endif
#if LFX_SSS_HALF_RES
    ivec2 last = textureSize(LfxSubsurfacePosition, 0) - 1;
    for (int corner = 0; corner < 4; ++corner) {
        // Source gather order is bottom-left, bottom-right, top-right, top-left.
        int row = corner < 2 ? 1 : 0;
        int column = corner == 1 || corner == 2 ? 1 : 0;
        if (SubsurfaceControls.w != 0) row = 1 - row;
        ivec2 samplePixel = min(pixel * 2 + ivec2(column, row), last);
        vec4 geometry;
        if (lfx_subsurface_pixel_geometry(samplePixel, geometry)) {
            sum += vec4(texelFetch(LfxSubsurfaceInput, samplePixel, 0).rgb, lfx_subsurface_scene_depth(geometry));
            count += 1.0;
#if LFX_SSS_AFIS
            reference = samplePixel;
#endif
        }
    }
#else
    vec4 geometry;
    if (lfx_subsurface_pixel_geometry(pixel, geometry)) {
        float depth = lfx_subsurface_scene_depth(geometry);
        float noise = lfx_interleaved_gradient_noise(vec2(pixel), float(SubsurfaceControls.z & 7));
        sum = vec4(texelFetch(LfxSubsurfaceInput, pixel, 0).rgb, depth * (1.0 + noise / 1024.0));
        count = 1.0;
#if LFX_SSS_AFIS
        reference = pixel;
#endif
    }
#endif
    fragColor = sum / max(count, 1.0);
#if LFX_SSS_AFIS
    subsurfaceSelection = vec4(0.0);
    if (count > 0.0) {
        vec4 geometry = texelFetch(LfxSubsurfacePosition, reference, 0);
        int profile = lfx_subsurface_profile_id(lfx_subsurface_custom_data(reference, geometry));
        subsurfaceSelection = vec4(vec2(reference), float(profile + 1), 0.0);
    }
#endif
}
#elif LFX_SSS_PASS == 1 || LFX_SSS_PASS == 2
void main() {
#if LFX_SSS_AFIS && LFX_SSS_PASS == 2
    subsurfaceHistory = vec4(0.0);
#endif
    ivec2 inputPixel = ivec2(gl_FragCoord.xy);
    vec4 center = texelFetch(LfxSubsurfaceInput, inputPixel, 0);
    if (center.a <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }

    ivec2 pixel = lfx_subsurface_pixel(texCoord);
    vec4 geometry;
    int profile = -1;
#if LFX_SSS_AFIS
    bool validGeometry = lfx_subsurface_reference(texCoord, pixel, geometry, profile);
#else
    bool validGeometry = lfx_subsurface_pixel_geometry(pixel, geometry);
#endif
    if (!validGeometry) {
        fragColor = vec4(center.rgb, LFX_SSS_PASS == 2 ? 1.0 : center.a);
        return;
    }
    vec4 customData = lfx_subsurface_custom_data(pixel, geometry);
#if !LFX_SSS_AFIS
    profile = lfx_subsurface_profile_id(customData);
#endif
#if LFX_SSS_AFIS
    if (lfx_subsurface_uses_burley(profile)) {
#if LFX_SSS_PASS == 1
        fragColor = lfx_burley_diffusion(inputPixel, pixel, texCoord, geometry, center, customData, profile);
#else
        subsurfaceHistory = lfx_burley_update_history(texCoord, geometry, center);
        fragColor = vec4(center.rgb, 1.0);
#endif
        return;
    }
#endif
    int kernel = SubsurfaceControls.x == 2 ? 6 : SubsurfaceControls.x == 1 ? 19 : 28;
    int count = SubsurfaceControls.x == 2 ? 13 : SubsurfaceControls.x == 1 ? 9 : 6;
    vec3 boundary = lfx_subsurface_kernel(profile, 4).rgb;
    vec3 centralWeight = lfx_subsurface_kernel(profile, kernel).rgb;
    vec3 sums = centralWeight * center.rgb;
    vec3 weights = centralWeight + vec3(1e-5);
    float scale = SubsurfaceParams.x * 1024.0 * customData.a / center.a;
#if LFX_SSS_PASS == 1
    vec2 direction = vec2(scale, 0.0);
#else
    vec2 direction = vec2(0.0, scale * SubsurfaceParams.z / SubsurfaceParams.w);
#endif
    for (int index = 1; index < count; ++index) {
        vec4 tap = lfx_subsurface_kernel(profile, kernel + index);
        vec2 delta = direction * tap.a * 3.0;
        for (int side = -1; side <= 1; side += 2) {
            vec2 uv = texCoord + delta * float(side);
            ivec2 samplePixel = lfx_subsurface_pixel(uv);
            vec4 sampleGeometry;
#if LFX_SSS_AFIS
            int sampleProfile;
            if (!lfx_subsurface_reference(uv, samplePixel, sampleGeometry, sampleProfile)) continue;
#else
            if (!lfx_subsurface_pixel_geometry(samplePixel, sampleGeometry)) continue;
            int sampleProfile = lfx_subsurface_profile_id(lfx_subsurface_custom_data(samplePixel, sampleGeometry));
#endif
            vec4 sampleValue = textureLod(LfxSubsurfaceInput, uv, 0.0);
            if (sampleValue.a <= 0.0) continue;
#if LFX_SSS_AFIS && LFX_SSS_PASS == 2
            sampleValue.a = textureLod(LfxSubsurfaceSetupDepth, uv, 0.0).a;
#endif
            float edgeWeight = 1.0 - clamp(0.03 * SubsurfaceParams.y * abs(center.a - sampleValue.a), 0.0, 1.0);
            vec3 weight = tap.rgb * edgeWeight;
            sums += weight * sampleValue.rgb * (sampleProfile == profile ? vec3(1.0) : boundary);
            weights += weight;
        }
    }
    vec3 color = mix(sums / weights, center.rgb, equal(weights, vec3(1e-5)));
    fragColor = vec4(color, LFX_SSS_PASS == 2 ? 1.0 : center.a);
}
#else
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 geometry;
    if (!lfx_subsurface_pixel_geometry(pixel, geometry)) discard;

    vec4 customData = lfx_subsurface_custom_data(pixel, geometry);
    int profile = lfx_subsurface_profile_id(customData);
    vec3 tint = lfx_subsurface_kernel(profile, 0).rgb;
    vec3 direct = texelFetch(LfxUnfilteredDiffuse, pixel, 0).rgb;
    vec4 filtered = textureLod(LfxSubsurfaceInput, texCoord, 0.0);
#if LFX_SSS_HALF_RES
    int lastTap = SubsurfaceControls.x == 2 ? 18 : SubsurfaceControls.x == 1 ? 27 : 33;
    float radius = lfx_subsurface_kernel(profile, lastTap).a * 3.0 * 1024.0;
    float radiusInPixels = SubsurfaceParams.x * radius * 2.0 * SubsurfaceParams.z / lfx_subsurface_scene_depth(geometry);
    tint *= clamp(radiusInPixels - 4.0, 0.0, 1.0) * clamp(customData.a * 10.0, 0.0, 1.0);
#endif
    vec3 diffuse = filtered.a > 0.0 ? mix(direct, filtered.rgb / max(filtered.a, 1e-5), tint) : direct;
    vec3 baseColor = texelFetch(LfxSubsurfaceMaterial, pixel, 0).rgb;
#if LFX_SSS_ADAPTIVE
    if (lfx_surface_particle_shading_model(geometry) == LFX_SHADING_MODEL_SUBSTRATE) {
        uvec4 header = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
        baseColor = lfx_adaptive_diffusion_pixel(pixel, header).baseColor;
    }
#endif
    fragColor = vec4(diffuse * baseColor, 0.0);
}
#endif
