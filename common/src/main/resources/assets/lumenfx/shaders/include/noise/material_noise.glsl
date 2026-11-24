// UE 5.8.4 material noise kernels, evaluated in source-coordinate centimeters.
vec3 lfx_noise_wrap(vec3 position, bool tiling, float repeatSize) {
    return tiling ? fract(position / repeatSize) * repeatSize : position;
}

uvec3 lfx_noise_pcg16(ivec3 cell) {
    uvec3 state = uvec3(cell) * 1664525u + 1013904223u;
    for (int roundIndex = 0; roundIndex < 2; ++roundIndex) {
        state.x += state.y * state.z;
        state.y += state.z * state.x;
        state.z += state.x * state.y;
    }
    return state >> 16u;
}

vec3 lfx_noise_gradient(uint bits) {
    return vec3(uvec3(bits) & uvec3(32768u, 16384u, 8192u))
        * vec3(1.0 / 16384.0, 1.0 / 8192.0, 1.0 / 4096.0) - 1.0;
}

vec3 lfx_noise_ramp(vec3 fraction) {
    return fraction * fraction * fraction * (fraction * (fraction * 6.0 - 15.0) + 10.0);
}

float lfx_noise_trilinear(float corners[8], vec3 w) {
    float a = mix(mix(corners[0], corners[1], w.x), mix(corners[2], corners[3], w.x), w.y);
    float b = mix(mix(corners[4], corners[5], w.x), mix(corners[6], corners[7], w.x), w.y);
    return mix(a, b, w.z);
}

vec3 lfx_noise_cube_corner(int index) {
    return vec3(ivec3(index, index >> 1, index >> 2) & ivec3(1));
}

float lfx_noise_bbs(float seed) {
    float state = fract(seed / 4093.0);
    state = fract(state * state * 4093.0);
    return fract(state * state * 4093.0);
}

float lfx_noise_perlin(vec3 position, bool tiling, float repeatSize, bool valueNoise) {
    vec3 cell = floor(position);
    vec3 fraction = fract(position);
    const vec3 primes = vec3(19.0, 47.0, 101.0);
    float originSeed = dot(cell, primes);
    float corners[8];
    for (int i = 0; i < 8; ++i) {
        vec3 offset = lfx_noise_cube_corner(i);
        float seed = tiling ? dot(primes, lfx_noise_wrap(cell + offset, true, repeatSize))
            : ((originSeed + offset.x * primes.x) + offset.y * primes.y) + offset.z * primes.z;
        corners[i] = valueNoise ? lfx_noise_bbs(seed) * 2.0 - 1.0
            : dot(lfx_noise_gradient(lfx_noise_pcg16(ivec3(int(seed), 0, 0)).x), fraction - offset);
    }
    return lfx_noise_trilinear(corners, lfx_noise_ramp(fraction));
}

vec3 lfx_noise_voronoi_offset(vec3 seed, int quality) {
    vec3 sampleOffset = vec3(lfx_noise_pcg16(ivec3(seed))) / 65535.0 - 0.5;
    if (quality <= 2) return normalize(sampleOffset) * 0.2588;
    if (quality == 3) return normalize(sampleOffset) * 0.3090;
    return sampleOffset;
}

void lfx_noise_voronoi_candidate(inout vec4 best, vec3 cell, vec3 fraction, vec3 offset,
        float seedOffset, int quality, bool tiling, float repeatSize, bool distanceOnly) {
    vec3 point = offset + lfx_noise_voronoi_offset(
        lfx_noise_wrap(cell + offset, tiling, repeatSize) + seedOffset, quality);
    vec3 relative = fraction - point;
    float distanceSquared = dot(relative, relative);
    if (distanceOnly) best.w = min(best.w, distanceSquared);
    else if (distanceSquared <= best.w) best = vec4(cell + point, distanceSquared);
}

vec4 lfx_noise_voronoi(vec3 position, int quality, bool tiling, float repeatSize, bool distanceOnly) {
    vec3 fraction = fract(position);
    vec3 cell = floor(position);
    vec3 shiftedFraction = fract(position + 0.5);
    vec3 shiftedCell = floor(position + 0.5);
    vec4 best = vec4(0.0, 0.0, 0.0, 100.0);
    if (quality == 3) {
        for (int x = -1; x <= 1; ++x) for (int y = -1; y <= 1; ++y) for (int z = -1; z <= 1; ++z) {
            lfx_noise_voronoi_candidate(best, shiftedCell, shiftedFraction, vec3(x, y, z), 0.0,
                quality, tiling, repeatSize, distanceOnly);
        }
    } else {
        for (int x = 0; x <= 1; ++x) for (int y = 0; y <= 1; ++y) for (int z = 0; z <= 1; ++z) {
            vec3 offset = vec3(x, y, z);
            lfx_noise_voronoi_candidate(best, cell, fraction, offset, 0.0, quality, tiling, repeatSize, distanceOnly);
            if (quality == 2) lfx_noise_voronoi_candidate(best, shiftedCell, shiftedFraction, offset, 467.0,
                quality, tiling, repeatSize, distanceOnly);
        }
    }
    if (quality >= 4) {
        for (int x = -1; x <= 2; x += 3) for (int y = 0; y <= 1; ++y) for (int z = 0; z <= 1; ++z) {
            vec3 offset = vec3(x, y, z);
            lfx_noise_voronoi_candidate(best, cell, fraction, offset.xyz, 0.0, quality, tiling, repeatSize, distanceOnly);
            lfx_noise_voronoi_candidate(best, cell, fraction, offset.yzx, 0.0, quality, tiling, repeatSize, distanceOnly);
            lfx_noise_voronoi_candidate(best, cell, fraction, offset.zxy, 0.0, quality, tiling, repeatSize, distanceOnly);
        }
    }
    return vec4(best.xyz, sqrt(best.w));
}

mat3x4 lfx_noise_simplex_jacobian(vec3 position, bool tiling, float repeatSize) {
    vec3 skewed = floor(position + position.x / 3.0 + position.y / 3.0 + position.z / 3.0);
    vec3 base = skewed - skewed.x / 6.0 - skewed.y / 6.0 - skewed.z / 6.0;
    vec3 fraction = position - base;
    vec3 rank = step(fraction.yzx, fraction);
    vec3 reverseRank = 1.0 - rank.zxy;
    mat4x3 corners = mat4x3(base, base + (min(rank, reverseRank) - 1.0 / 6.0),
        base + (max(rank, reverseRank) - 1.0 / 3.0), base + 0.5);
    mat3x4 jacobian = mat3x4(0.0);
    const float scale = 1024.0 / 375.0;
    for (int i = 0; i < 4; ++i) {
        vec3 relative = position - corners[i];
        float squared = clamp(2.0 * dot(relative, relative), 0.0, 1.0);
        float weight = scale + squared * (-3.0 * scale + squared * (3.0 * scale - squared * scale));
        vec3 derivative = (-12.0 * scale + squared * (24.0 * scale - squared * 12.0 * scale)) * relative;
        uvec3 bits = lfx_noise_pcg16(ivec3(floor(lfx_noise_wrap(6.0 * corners[i] + 0.5, tiling, repeatSize))));
        for (int component = 0; component < 3; ++component) {
            vec3 gradient = lfx_noise_gradient(bits[component]);
            float contribution = dot(gradient, relative);
            jacobian[component] += vec4(weight * gradient + derivative * contribution, weight * contribution);
        }
    }
    return jacobian;
}

vec4 lfx_material_vector_noise(vec3 position, int quality, int mode, bool tiling, float repeatSize) {
    if (mode == 0) {
        vec3 cell = floor(lfx_noise_wrap(position, tiling, repeatSize));
        return vec4(vec3(lfx_noise_pcg16(ivec3(cell))) / 65535.0, 1.0);
    }
    if (mode == 4) return lfx_noise_voronoi(position, quality, tiling, repeatSize, false);
    mat3x4 jacobian = lfx_noise_simplex_jacobian(position, tiling, repeatSize);
    if (mode == 1) return vec4(jacobian[0].w, jacobian[1].w, jacobian[2].w, 1.0);
    if (mode == 2) return jacobian[0];
    return vec4(jacobian[2].y - jacobian[1].z, jacobian[0].z - jacobian[2].x,
        jacobian[1].x - jacobian[0].y, 1.0);
}

#if LFX_NOISE_GRADIENT_TEXTURE
uniform sampler2D LfxMaterialNoiseGradient;

vec3 lfx_noise_texture_gradient(vec3 cell) {
    vec2 uv = (cell.xy + cell.z * vec2(17.0, 89.0) + 0.5) / 128.0;
    return textureLod(LfxMaterialNoiseGradient, uv, 0.0).xyz * 2.0 - 1.0;
}

float lfx_noise_texture_perlin(vec3 position, float repeatSize) {
    vec3 fraction = fract(position);
    // The native gradient texture always wraps, even if the tiling property is disabled.
    vec3 low = lfx_noise_wrap(floor(position), true, repeatSize);
    vec3 high = lfx_noise_wrap(low + 1.0, true, repeatSize);
    float corners[8];
    for (int i = 0; i < 8; ++i) {
        vec3 offset = lfx_noise_cube_corner(i);
        vec3 cell = mix(low, high, notEqual(offset, vec3(0.0)));
        corners[i] = dot(lfx_noise_texture_gradient(cell), fraction - offset);
    }
    return lfx_noise_trilinear(corners, lfx_noise_ramp(fraction));
}

float lfx_noise_texture_simplex(vec3 position) {
    vec3 skewed = position + dot(position, vec3(1.0 / 3.0));
    vec3 base = floor(skewed);
    vec3 fraction = skewed - base;
    float largest = max(fraction.x, max(fraction.y, fraction.z));
    float smallest = min(fraction.x, min(fraction.y, fraction.z));
    mat4x3 corners = mat4x3(base, base + 1.0, base + vec3(equal(fraction, vec3(largest))),
        base + vec3(notEqual(fraction, vec3(smallest))));
    vec4 contributions;
    for (int i = 0; i < 4; ++i) {
        vec3 corner = corners[i];
        vec3 relative = position - (corner - dot(corner, vec3(1.0 / 6.0)));
        float weight = clamp(0.6 - dot(relative, relative), 0.0, 1.0);
        weight *= weight;
        weight *= weight;
        contributions[i] = dot(lfx_noise_texture_gradient(corner), relative) * weight;
    }
    return 32.0 * (contributions.x + contributions.y + contributions.z + contributions.w);
}
#endif

#if LFX_NOISE_VOLUME_TEXTURE
uniform sampler2D LfxMaterialNoiseVolume;

float lfx_noise_texture_volume(vec3 position) {
    vec3 wrapped = fract(position / 15.0) * 15.0;
    vec3 fraction = fract(wrapped);
    vec3 cell = wrapped - fraction;
    vec3 weight = lfx_noise_ramp(fraction);
    // A 16×256 surface holds all sixteen slices, including the replicated last slice.
    vec2 uv = (cell.xy + weight.xy + 0.5 + vec2(0.0, cell.z * 16.0)) / vec2(16.0, 256.0);
    vec4 sliceA = textureLod(LfxMaterialNoiseVolume, uv, 0.0);
    vec4 sliceB = textureLod(LfxMaterialNoiseVolume, uv + vec2(0.0, 1.0 / 16.0), 0.0);
    vec4 sampleValue = mix(sliceA, sliceB, weight.z);
    vec3 gradient = sampleValue.xyz * 255.0 / 127.0 - 1.0;
    return dot(wrapped, gradient) - (sampleValue.w * 255.0 - 127.0);
}
#endif

float lfx_noise_scalar_kernel(vec3 position, int quality, int mode, bool tiling, float repeatSize) {
#if LFX_NOISE_GRADIENT_TEXTURE
    if (mode == 0) return lfx_noise_texture_simplex(position);
    if (mode == 1) return lfx_noise_texture_perlin(position, repeatSize);
#endif
#if LFX_NOISE_VOLUME_TEXTURE
    if (mode == 2) return lfx_noise_texture_volume(position);
#endif
    if (mode == 3 || mode == 4) return lfx_noise_perlin(position, tiling, repeatSize, mode == 4);
    return lfx_noise_voronoi(position, quality, tiling, repeatSize, true).w * 2.0 - 1.0;
}

float lfx_material_noise(vec3 position, float scale, int quality, int mode, bool turbulence,
        int levels, float outputMin, float outputMax, float levelScale, float filterWidth, bool tiling, float repeatSize) {
    position *= scale;
    filterWidth *= scale;
    float result = 0.0;
    float amplitude = 1.0;
    float inverseLevelScale = 1.0 / levelScale;
    for (int level = 0; level < levels; ++level) {
        amplitude *= clamp(1.0 - filterWidth, 0.0, 1.0);
        float noise = lfx_noise_scalar_kernel(position, quality, mode, tiling, repeatSize);
        result += (turbulence ? abs(noise) : noise) * amplitude;
        position *= levelScale;
        repeatSize *= levelScale;
        amplitude *= inverseLevelScale;
        filterWidth *= levelScale;
    }
    if (!turbulence) result = result * 0.5 + 0.5;
    return mix(outputMin, outputMax, result);
}
