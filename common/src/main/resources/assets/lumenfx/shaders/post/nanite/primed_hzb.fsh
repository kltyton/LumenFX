#version 330

uniform sampler2D CoarseDepth;
uniform sampler2D SceneDepth;
layout(std140) uniform NanitePrimeProjection {
    vec4 DepthProjection;
    vec4 DepthBiasAndFlags;
};
out float FurthestDepth;

float biasCoarseDepth(float deviceDepth) {
    if (deviceDepth == 0.0) return 0.0;
    bool zeroToOne = DepthBiasAndFlags.y != 0.0;
    float ndc = zeroToOne ? deviceDepth : deviceDepth * 2.0 - 1.0;
    float divisor = ndc * DepthProjection.z - DepthProjection.x;
    if (divisor == 0.0) return 0.0;
    float z = (DepthProjection.y - ndc * DepthProjection.w) / divisor - DepthBiasAndFlags.x;
    float biased = (DepthProjection.x * z + DepthProjection.y) / (DepthProjection.z * z + DepthProjection.w);
    return clamp(zeroToOne ? biased : biased * 0.5 + 0.5, 0.0, 1.0);
}

void main() {
    ivec2 size = textureSize(CoarseDepth, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    bool topDown = DepthBiasAndFlags.w != 0.0;
    if (!topDown) pixel.y = max(0, size.y - 1 - pixel.y);
    ivec2 coarsePixel = min(pixel, max(size - 2, ivec2(0)));
    if (!topDown) coarsePixel.y = size.y - 1 - coarsePixel.y;
    float coarse = biasCoarseDepth(texelFetch(CoarseDepth, coarsePixel, 0).r);
    float scene = 0.0;
    if (DepthBiasAndFlags.z != 0.0) {
        ivec2 sceneSize = textureSize(SceneDepth, 0);
        // The unscaled first level gathers scene depth in its original texel coordinates.
        ivec2 origin = ivec2(floor(min(vec2(pixel) + 0.25, vec2(size) - 1.5) - 0.5));
        ivec2 first = clamp(origin, ivec2(0), sceneSize - 1);
        ivec2 next = clamp(origin + 1, ivec2(0), sceneSize - 1);
        if (!topDown) {
            first.y = sceneSize.y - 1 - first.y;
            next.y = sceneSize.y - 1 - next.y;
        }
        float a = texelFetch(SceneDepth, first, 0).r;
        float b = texelFetch(SceneDepth, ivec2(next.x, first.y), 0).r;
        float c = texelFetch(SceneDepth, ivec2(first.x, next.y), 0).r;
        float d = texelFetch(SceneDepth, next, 0).r;
        scene = min(min(a, b), min(c, d));
    }
    FurthestDepth = max(coarse, scene);
}
