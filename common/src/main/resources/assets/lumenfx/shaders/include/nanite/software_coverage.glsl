#moj_import <lumenfx:nanite/triangle_coverage.glsl>

layout(local_size_x = 64) in;
layout(std430) readonly buffer NaniteBinTriangles { uvec2 lfxNaniteBinTriangles[]; };
layout(std430) readonly buffer NaniteRasterCommands { uvec4 lfxNaniteRasterCommands[]; };
layout(std430) buffer NaniteSoftwareDepth { uint lfxNaniteDepthWords[]; };
layout(std430) buffer NaniteSoftwarePrimitives { uint lfxNanitePrimitiveWords[]; };
layout(std140) uniform NaniteSoftwareDispatch { uvec4 NaniteSoftwareRasterInfo; };

void main() {
    uvec4 command = lfxNaniteRasterCommands[NaniteShadingOffsets.w];
    uint lane = gl_GlobalInvocationID.x + gl_GlobalInvocationID.y * NaniteSoftwareRasterInfo.x;
    if (lane >= command.x / 3u) return;
    uint index = command.z / 3u + lane;
    uvec4 primitive = lfxNanitePrimitives[index];
    bool reversed = ((primitive.w ^ (primitive.w >> 1u)) & 1u) != 0u;
    vec4 clip[3];
    for (uint corner = 0u; corner < 3u; ++corner) {
        uint source = reversed && corner != 0u ? 3u - corner : corner;
        clip[corner] = NaniteCurrentClip * lfxNaniteVertices[(index * 3u + source) * 9u];
    }
    ivec2 size = ivec2(round(1.0 / NaniteShadingViewport.xy));
    bool topDown = NaniteShadingViewport.z < 0.0;
    bool twoSided = NaniteSoftwareRasterInfo.y != 0u;
    NaniteRasterTriangle triangle = NaniteRasterTriangleFromClip(clip, size, NaniteShadingViewport.w > 0.5, twoSided);
    if (!triangle.visible) return;

    lfxNaniteSoftwareTriangle = index;
    lfxNaniteSoftwareTwoSidedSign = triangle.backFace ? -1.0 : 1.0;
    vec3 dx = triangle.stepX * triangle.edgeScale;
    vec3 dy = triangle.stepY * triangle.edgeScale * -NaniteShadingViewport.z;
    lfxNaniteSoftwareBarycentrics.dx = reversed ? dx.xzy : dx;
    lfxNaniteSoftwareBarycentrics.dy = reversed ? dy.xzy : dy;
    uvec2 sourcePrimitive = lfxNaniteBinTriangles[index];
    uint packedValue = ((sourcePrimitive.x + 1u) << 8u) | sourcePrimitive.y;
    bool identity = NaniteSoftwareRasterInfo.z != 0u;
    for (int y = triangle.minimum.y; y <= triangle.maximum.y; ++y) {
        int row = topDown ? y : size.y - 1 - y;
        for (int x = triangle.minimum.x; x <= triangle.maximum.x; ++x) {
            vec3 edge = NaniteRasterEdgesAtPixel(triangle, ivec2(x, y));
            float depth;
            if (!NaniteRasterDepthAtEdges(triangle, edge, NaniteShadingPixelOffset.z != 0, depth)) continue;
            vec3 weighted = edge * triangle.inverseW;
            vec3 weights = weighted / (weighted.x + weighted.y + weighted.z);
            lfxNaniteSoftwareBarycentrics.value = reversed ? weights.xzy : weights;
            lfxNaniteSoftwareFragCoord = vec4(vec2(x, row) + 0.5, depth, 1.0);
            lfxNaniteSoftwareDepth = depth;
            if (!lfx_nanite_evaluate_coverage()) continue;
            uint pixel = uint(row * size.x + x);
            uint deviceDepth = floatBitsToUint(clamp(lfxNaniteSoftwareDepth, 0.0, 1.0));
            if (identity) {
                if (deviceDepth == lfxNaniteDepthWords[pixel]) atomicMax(lfxNanitePrimitiveWords[pixel], packedValue);
            } else atomicMax(lfxNaniteDepthWords[pixel], deviceDepth);
        }
    }
}
