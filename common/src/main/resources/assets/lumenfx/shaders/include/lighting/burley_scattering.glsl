uniform sampler2D LfxSubsurfaceQuality;
uniform sampler2D LfxBurleyRadiance;
#if LFX_SSS_PASS == 1
uniform sampler2D LfxSubsurfaceNormal;
#if LFX_SSS_MIPS
#moj_import <lumenfx:lighting/burley_mip_settings.glsl>
#endif
#endif
#if LFX_SSS_VELOCITY
#moj_import <lumenfx:scene_velocity.glsl>
uniform sampler2D LfxSubsurfaceVelocity;
#endif
#if LFX_SSS_PASS == 2
uniform sampler2D LfxSubsurfaceSetup;
uniform sampler2D LfxSubsurfaceSetupDepth;
#endif

bool lfx_subsurface_uses_burley(int profile) {
    if (profile == LFX_PER_PIXEL_DIFFUSION) return true;
    return lfx_subsurface_profile_load(profile, 4).a < 0.01;
}

vec4 lfx_burley_history(vec2 uv, vec4 geometry, out bool valid) {
    valid = false;
    if (SubsurfaceBurley.z == 0) return vec4(0.0);

    float ndcZ = SubsurfaceControls.y == 0 ? 2.0 * geometry.w - 1.0 : geometry.w;
    vec4 oldClip = SubsurfacePreviousClip * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    vec2 oldUv = oldClip.w > 0.0 ? oldClip.xy * (0.5 / oldClip.w) + 0.5 : vec2(-1.0);
#if LFX_SSS_VELOCITY
    vec4 velocity = texelFetch(LfxSubsurfaceVelocity, lfx_subsurface_pixel(uv), 0);
    if (velocity.x > 0.0)
        oldUv = uv - lfx_decode_scene_velocity(velocity).xy * vec2(0.5, -0.5) - SubsurfaceJitter.zw;
#endif
    valid = all(greaterThan(oldUv, vec2(0.0))) && all(lessThan(oldUv, vec2(1.0)));
    return valid ? textureLod(LfxSubsurfaceQuality, oldUv, 0.0) : vec4(0.0);
}

#if LFX_SSS_PASS == 1
uint lfx_burley_sequence_start(uvec3 value) {
    value = value * 1664525u + 1013904223u;
    value.x += value.y * value.z;
    value.y += value.z * value.x;
    value.z += value.x * value.y;
    value.x += value.y * value.z;
    return value.x >> 16u;
}

vec4 lfx_burley_diffusion(ivec2 inputPixel, ivec2 pixel, vec2 uv, vec4 geometry, vec4 center, vec4 custom, int profile) {
    bool historyValid;
    vec4 history = lfx_burley_history(uv, geometry, historyValid);
    float sampleEstimate = history.b / 0.0001;
#if LFX_SSS_HALF_RES
    float previousCount = history.g * 9.0;
    sampleEstimate = sampleEstimate * previousCount - previousCount;
#endif
    int samples = SubsurfaceBurley.x > 0 ? SubsurfaceBurley.x : int(clamp(sampleEstimate, 8.0, 64.0));
    float worldUnitScale;
    vec4 albedo;
    vec4 dmfp;
#if LFX_SSS_ADAPTIVE
    if (profile == LFX_PER_PIXEL_DIFFUSION) {
        uvec4 header = texelFetch(LfxAdaptiveParticleWords, ivec3(pixel, 0), 0);
        LfxSlabDiffusionPixel data = lfx_adaptive_diffusion_pixel(pixel, header);
        vec3 mfp = data.meanFreePath * 10.0;
        float maximum = max(mfp.x, max(mfp.y, mfp.z));
        int index = mfp.x == maximum ? 0 : mfp.y == maximum ? 1 : 2;
        albedo = vec4(data.baseColor, data.baseColor[index]);
        vec3 perpendicular = abs(data.baseColor - 0.8);
        perpendicular = 1.85 - data.baseColor + 7.0 * perpendicular * perpendicular * perpendicular;
        vec3 spectral = data.baseColor - 0.33;
        spectral = 3.5 + 100.0 * spectral * spectral * spectral * spectral;
        vec3 diffuseMfp = mfp * spectral / (0.6 * perpendicular);
        dmfp = max(vec4(diffuseMfp, diffuseMfp[index]), vec4(1e-6));
        worldUnitScale = 1.0;
    } else
#endif
    {
        worldUnitScale = lfx_subsurface_profile_load(profile, 0).a * 500.0;
        albedo = lfx_subsurface_profile_load(profile, 1);
        dmfp = max(lfx_subsurface_profile_load(profile, 2) * (500.0 * custom.a), vec4(1e-6));
    }
    if (custom.a <= 0.1 || worldUnitScale <= 0.0) return vec4(center.rgb, float(samples));
    vec4 offsetAlbedo = albedo - 0.33;
    vec4 spectral = 3.5 + 100.0 * offsetAlbedo * offsetAlbedo * offsetAlbedo * offsetAlbedo;
    vec4 meanDistance = dmfp / spectral;
    vec2 scale = (SubsurfaceParams.x * worldUnitScale * 0.3 / center.a)
            * vec2(1.0, SubsurfaceParams.z / SubsurfaceParams.w);
    float centerRadius = 0.5 * dot(1.0 / (SubsurfaceParams.zw * scale), vec2(1.0));
    vec4 centerCdf = 1.0 - 0.25 * exp(-centerRadius / meanDistance) - 0.75 * exp(-centerRadius / (3.0 * meanDistance));
    if (centerCdf.w >= 1.0) return vec4(center.rgb, float(samples));

    uint seed = lfx_burley_sequence_start(uvec3(inputPixel, SubsurfaceControls.z));
    const float plastic = 1.324717957244746;
    const vec2 sequence = vec2(1.0 / plastic, 1.0 / (plastic * plastic));
    const float tau = 6.283185307179586;
    const float invEightPi = 0.039788735772974;
    vec3 normal = texelFetch(LfxSubsurfaceNormal, pixel, 0).xyz;
    vec3 boundary = lfx_subsurface_kernel(profile, 4).rgb;
    vec3 light = vec3(0.0), weightSum = vec3(0.0), colorBleed = vec3(0.0);
#if LFX_SSS_MIPS
    bool useMips = lfx_burley_use_mips();
#endif
    for (int index = 0; index < samples; ++index) {
        vec2 random = fract(sequence * float(seed + uint(index)));
        float u = mix(centerCdf.w, 1.0, random.x);
        float radius = max(meanDistance.w * (-0.6 * u - 2.0) * log(max(1.0 - u, 1e-7)), 1e-5);
        float scaledRadius = -radius / meanDistance.w;
        float pdf = max(0.25 / meanDistance.w * (exp(scaledRadius) + exp(scaledRadius / 3.0)), 1e-5);
        float angle = random.y * tau;
        vec2 sampleUv = uv + scale * radius * vec2(cos(angle), sin(angle));
        int mip = 0;
#if LFX_SSS_MIPS
        if (useMips) {
            float distribution = 0.0625 * float(samples) * pdf / (dmfp.w * dmfp.w * scale.x * scale.y);
            float level = 0.5 * max(-log2(distribution), 0.0);
#if LFX_SSS_HALF_RES
            level -= 1.0;
#endif
            mip = int(clamp(ceil(level), 0.0, float(BurleyMipSettings.y)));
        }
#endif
        vec2 margin = 0.5 / vec2(textureSize(LfxBurleyRadiance, mip));
        sampleUv = clamp(sampleUv, margin, 1.0 - margin);
        ivec2 samplePixel = lfx_subsurface_pixel(sampleUv);
        vec4 sampleGeometry;
        int localProfile;
        if (!lfx_subsurface_reference(sampleUv, samplePixel, sampleGeometry, localProfile)) {
            colorBleed += vec3(1.0);
            continue;
        }
        colorBleed += localProfile == profile ? vec3(1.0) : boundary;
        vec4 sampleValue = textureLod(LfxBurleyRadiance, sampleUv, float(mip));
        vec3 sampleNormal = texelFetch(LfxSubsurfaceNormal, samplePixel, 0).xyz;
        float normalWeight = SubsurfaceBurley.y == 0 ? 1.0 : sqrt(clamp(dot(sampleNormal, normal) * 0.5 + 0.5, 0.0, 1.0));
        float depth = (sampleValue.a - center.a) * 10.0 / worldUnitScale;
        vec3 distance = -sqrt(radius * radius + depth * depth) / meanDistance.rgb;
        vec3 diffusion = max((exp(distance) + exp(distance / 3.0)) * (invEightPi / meanDistance.rgb), vec3(1e-12));
        vec3 weight = diffusion * (normalWeight / pdf);
        light += weight * sampleValue.rgb;
        weightSum += weight;
    }
    light /= max(weightSum * 0.99995, vec3(1e-20));
    light *= colorBleed / float(samples);
    light = mix(light, center.rgb, centerCdf.rgb);
    return vec4(mix(center.rgb, light, clamp((custom.a - 0.1) / 0.1, 0.0, 1.0)), float(samples));
}
#else
vec4 lfx_burley_update_history(vec2 uv, vec4 geometry, vec4 center) {
    if (center.a <= 0.0) return vec4(0.0);
    bool valid;
    vec4 history = lfx_burley_history(uv, geometry, valid);
    float weight = valid ? 0.2 : 1.0;
    vec3 sampled = max(textureLod(LfxBurleyRadiance, uv, 0.0).rgb, vec3(0.0));
    vec3 control = max(textureLod(LfxSubsurfaceSetup, uv - SubsurfaceJitter.xy, 0.0).rgb, vec3(0.0));
    vec3 luminance = vec3(0.2126390059, 0.7151686788, 0.0721923154);
    float residual = dot(pow(sampled, vec3(1.0 / 2.2)) - pow(control, vec3(1.0 / 2.2)), luminance);
    float delta = residual - history.a;
    float variance = (1.0 - weight) * (history.b + weight * delta * delta);
#if LFX_SSS_HALF_RES
    float count = mix(history.g, center.a, weight);
#else
    float count = history.b * center.a;
#endif
    return vec4(mix(history.r, center.a / 64.0, weight), count, variance, mix(history.a, residual, weight));
}
#endif
