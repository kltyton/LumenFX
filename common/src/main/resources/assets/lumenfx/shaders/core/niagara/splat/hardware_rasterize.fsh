#version 330

layout(std140) uniform GaussianSplatRasterView {
    vec4 ViewToClip22_23_32_33;
    vec4 InvViewSize;
    vec4 InvDeviceZToWorldZ;
    vec4 FalloffParameters;
    vec4 DepthParameters;
    ivec4 RasterFlags;
};

#if LFX_WRITE_STOCHASTIC_DEPTH && LFX_USE_BLUE_NOISE
uniform sampler2D BlueNoiseScalarTexture;
#endif

in vec2 vCorner;
flat in vec4 vColor;
flat in uint vSplatIndex;

layout(location = 0) out vec4 fragColor;
#if LFX_WRITE_EXPECTED_DEPTH
layout(location = 1) out vec4 fragSecondary;
#endif

float lfx_splat_falloff(float r2) {
    if (r2 >= 4.0) return 0.0;
    return clamp(FalloffParameters.x * exp(-r2) + FalloffParameters.y * r2 + FalloffParameters.z, 0.0, 1.0);
}

void main() {
    float weight;
#if LFX_SUPERSAMPLE
    int grid = max(1, RasterFlags.z);
    vec2 dCdx = dFdx(vCorner);
    vec2 dCdy = dFdy(vCorner);
    float sum = 0.0;
    float invGrid = 1.0 / float(grid);
    for (int sy = 0; sy < grid; ++sy) {
        float oy = -0.5 + (float(sy) + 0.5) * invGrid;
        for (int sx = 0; sx < grid; ++sx) {
            float ox = -0.5 + (float(sx) + 0.5) * invGrid;
            vec2 sampleCorner = vCorner + dCdx * ox + dCdy * oy;
            sum += lfx_splat_falloff(4.0 * dot(sampleCorner, sampleCorner));
        }
    }
    weight = sum * (invGrid * invGrid);
#else
    float r2 = 4.0 * dot(vCorner, vCorner);
    if (r2 >= 4.0) discard;
    weight = lfx_splat_falloff(r2);
#endif

    float srcAlpha = clamp(vColor.a * weight, 0.0, 1.0);
    if (srcAlpha < FalloffParameters.w) discard;

#if LFX_WRITE_STOCHASTIC_DEPTH
    ivec2 pixel = ivec2(gl_FragCoord.xy);
#if LFX_USE_BLUE_NOISE
    ivec2 noiseCoord = ivec2(pixel.x & 127, (pixel.y & 127) + int((uint(RasterFlags.y) + vSplatIndex) & 63u) * 128);
    float threshold = texelFetch(BlueNoiseScalarTexture, noiseCoord, 0).r;
#else
    uint seed = uint(pixel.x) * 1973u + uint(pixel.y) * 9277u + uint(RasterFlags.y) * 26699u + vSplatIndex * 374761393u;
    seed = (seed ^ (seed >> 13u)) * 1274126177u;
    float threshold = float((seed ^ (seed >> 16u)) & 0x00FFFFFFu) * (1.0 / 16777216.0);
#endif
    fragColor = vec4(srcAlpha > threshold ? gl_FragCoord.z : 0.0, 0.0, 0.0, 0.0);
#else
    fragColor = vec4(vColor.rgb * (srcAlpha * DepthParameters.y), srcAlpha);
#if LFX_WRITE_EXPECTED_DEPTH
    float linearZ = (gl_FragCoord.z * InvDeviceZToWorldZ.x + InvDeviceZToWorldZ.y
            + 1.0 / (gl_FragCoord.z * InvDeviceZToWorldZ.z - InvDeviceZToWorldZ.w)) / DepthParameters.x;
    fragSecondary = vec4(linearZ * srcAlpha, 0.0, 0.0, srcAlpha);
#endif
#endif
}
