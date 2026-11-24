#ifndef LFX_NANITE_TRIANGLE_COVERAGE
#define LFX_NANITE_TRIANGLE_COVERAGE

#if LFX_NANITE_COVERAGE_HLSL
#define LFX_NANITE_VEC2 float2
#define LFX_NANITE_VEC3 float3
#define LFX_NANITE_VEC4 float4
#define LFX_NANITE_IVEC2 int2
#define LFX_NANITE_UNROLL [unroll]
#define LFX_NANITE_STATIC static
#define LFX_NANITE_GE(a, b) all((a) >= (b))
#define LFX_NANITE_LE(a, b) all((a) <= (b))
#define LFX_NANITE_SATURATE(v) saturate(v)
#define LFX_NANITE_DEPTH_BITS(v) asuint(v)
#else
#define LFX_NANITE_VEC2 vec2
#define LFX_NANITE_VEC3 vec3
#define LFX_NANITE_VEC4 vec4
#define LFX_NANITE_IVEC2 ivec2
#define LFX_NANITE_UNROLL
#define LFX_NANITE_STATIC
#define LFX_NANITE_GE(a, b) all(greaterThanEqual(a, b))
#define LFX_NANITE_LE(a, b) all(lessThanEqual(a, b))
#define LFX_NANITE_SATURATE(v) clamp(v, 0.0, 1.0)
#define LFX_NANITE_DEPTH_BITS(v) floatBitsToUint(v)
#endif

LFX_NANITE_STATIC const float NANITE_SUBPIXELS = 256.0;

struct NaniteRasterTriangle {
    LFX_NANITE_IVEC2 minimum;
    LFX_NANITE_IVEC2 maximum;
    LFX_NANITE_VEC3 edgeAtMinimum;
    LFX_NANITE_VEC3 stepX;
    LFX_NANITE_VEC3 stepY;
    LFX_NANITE_VEC3 depthPlane;
    LFX_NANITE_VEC3 inverseW;
    float edgeScale;
    bool backFace;
    bool visible;
};

NaniteRasterTriangle NaniteRasterTriangleFromClip(LFX_NANITE_VEC4 clip[3], LFX_NANITE_IVEC2 viewport,
                                                 bool zeroToOne, bool twoSided) {
    NaniteRasterTriangle tri;
    tri.visible = false;
    if (min(clip[0].w, min(clip[1].w, clip[2].w)) <= 0.0) return tri;
    LFX_NANITE_VEC3 vertices[3];
    LFX_NANITE_VEC2 screenScale = LFX_NANITE_VEC2(0.5, -0.5);
    LFX_NANITE_UNROLL
    for (uint vertex = 0u; vertex < 3u; ++vertex) {
        float inverseW = 1.0 / clip[vertex].w;
        tri.inverseW[vertex] = inverseW;
        LFX_NANITE_VEC3 ndc = clip[vertex].xyz * inverseW;
        vertices[vertex] = LFX_NANITE_VEC3(floor((ndc.xy * screenScale + 0.5) * LFX_NANITE_VEC2(viewport)
            * NANITE_SUBPIXELS + 0.5), zeroToOne ? ndc.z : ndc.z * 0.5 + 0.5);
    }

    LFX_NANITE_VEC2 e01 = vertices[0].xy - vertices[1].xy;
    LFX_NANITE_VEC2 e12 = vertices[1].xy - vertices[2].xy;
    LFX_NANITE_VEC2 e20 = vertices[2].xy - vertices[0].xy;
    float determinant = e01.y * e20.x - e01.x * e20.y;
    if (determinant == 0.0) return tri;
    tri.backFace = determinant > 0.0;
    if (tri.backFace && !twoSided) return tri;
    if (tri.backFace) {
        e01 = -e01;
        e12 = -e12;
        e20 = -e20;
    }

    LFX_NANITE_VEC2 minimum = min(vertices[0].xy, min(vertices[1].xy, vertices[2].xy));
    LFX_NANITE_VEC2 maximum = max(vertices[0].xy, max(vertices[1].xy, vertices[2].xy));
    tri.minimum = max(LFX_NANITE_IVEC2(floor((minimum + 127.0) / NANITE_SUBPIXELS)), LFX_NANITE_IVEC2(0, 0));
    tri.maximum = min(LFX_NANITE_IVEC2(floor((maximum - 129.0) / NANITE_SUBPIXELS)),
        min(viewport - 1, tri.minimum + 63));
    tri.visible = LFX_NANITE_LE(tri.minimum, tri.maximum);
    if (!tri.visible) return tri;

    LFX_NANITE_VEC2 origin = LFX_NANITE_VEC2(tri.minimum) * NANITE_SUBPIXELS + NANITE_SUBPIXELS * 0.5;
    LFX_NANITE_VEC2 p0 = vertices[0].xy - origin;
    LFX_NANITE_VEC2 p1 = vertices[1].xy - origin;
    LFX_NANITE_VEC2 p2 = vertices[2].xy - origin;
    LFX_NANITE_VEC3 edge = LFX_NANITE_VEC3(e12.y * p1.x - e12.x * p1.y, e20.y * p2.x - e20.x * p2.y,
        e01.y * p0.x - e01.x * p0.y);
    tri.edgeScale = NANITE_SUBPIXELS / (edge.x + edge.y + edge.z);
    LFX_NANITE_VEC3 deltaX = LFX_NANITE_VEC3(e12.x, e20.x, e01.x);
    LFX_NANITE_VEC3 deltaY = LFX_NANITE_VEC3(e12.y, e20.y, e01.y);
    edge -= LFX_NANITE_SATURATE(deltaY + LFX_NANITE_SATURATE(1.0 - deltaX));
    tri.edgeAtMinimum = edge / NANITE_SUBPIXELS;
    tri.stepX = -deltaY;
    tri.stepY = deltaX;
    tri.depthPlane = LFX_NANITE_VEC3(vertices[0].z, (vertices[1].z - vertices[0].z) * tri.edgeScale,
        (vertices[2].z - vertices[0].z) * tri.edgeScale);
    return tri;
}

LFX_NANITE_VEC3 NaniteRasterEdgesAtPixel(NaniteRasterTriangle tri, LFX_NANITE_IVEC2 pixel) {
    LFX_NANITE_IVEC2 offset = pixel - tri.minimum;
    return tri.edgeAtMinimum + float(offset.x) * tri.stepX + float(offset.y) * tri.stepY;
}

bool NaniteRasterDepthAtEdges(NaniteRasterTriangle tri, LFX_NANITE_VEC3 edge, bool clampNear, out float depth) {
    depth = tri.depthPlane.x + dot(tri.depthPlane.yz, edge.yz);
    return LFX_NANITE_GE(edge, LFX_NANITE_VEC3(0.0, 0.0, 0.0)) && depth >= 0.0 && (clampNear || depth <= 1.0);
}

bool NaniteRasterDepthAtPixel(NaniteRasterTriangle tri, LFX_NANITE_IVEC2 pixel, bool clampNear, out uint depth) {
    float z;
    bool covered = NaniteRasterDepthAtEdges(tri, NaniteRasterEdgesAtPixel(tri, pixel), clampNear, z);
    depth = LFX_NANITE_DEPTH_BITS(LFX_NANITE_SATURATE(z));
    return covered;
}

#undef LFX_NANITE_VEC2
#undef LFX_NANITE_VEC3
#undef LFX_NANITE_VEC4
#undef LFX_NANITE_IVEC2
#undef LFX_NANITE_UNROLL
#undef LFX_NANITE_STATIC
#undef LFX_NANITE_GE
#undef LFX_NANITE_LE
#undef LFX_NANITE_SATURATE
#undef LFX_NANITE_DEPTH_BITS
#endif
