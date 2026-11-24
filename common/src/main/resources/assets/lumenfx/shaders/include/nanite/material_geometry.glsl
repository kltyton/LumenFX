#ifndef LFX_NANITE_MATERIAL_GEOMETRY
#define LFX_NANITE_MATERIAL_GEOMETRY

#if LFX_NANITE_SOFTWARE_RASTER
layout(std430) readonly buffer NaniteShadingPrimitives { uvec4 lfxNanitePrimitives[]; };
layout(std430) readonly buffer NaniteShadingVertices { vec4 lfxNaniteVertices[]; };
layout(std430) readonly buffer NaniteVertexInterpolants { vec4 lfxNaniteVertexOutputs[]; };
#define LFX_NANITE_PRIMITIVE(i) lfxNanitePrimitives[i]
#define LFX_NANITE_VERTEX(i) lfxNaniteVertices[i]
#define LFX_NANITE_INTERPOLANT(i) lfxNaniteVertexOutputs[i]
#else
#if LFX_NANITE_RASTER_MATERIAL
flat in uvec2 primitiveData;
noperspective in float lfxNaniteUnclippedDepth;
in vec4 lfxNaniteCoverageUV01;
#else
uniform usampler2D NaniteVisiblePrimitives;
uniform sampler2D NaniteVisibleDepth;
#endif
uniform usamplerBuffer NanitePrimitiveLookup;
uniform usamplerBuffer NaniteShadingPrimitives;
uniform samplerBuffer NaniteShadingVertices;
uniform samplerBuffer NaniteVertexInterpolants;
#define LFX_NANITE_PRIMITIVE(i) texelFetch(NaniteShadingPrimitives, i)
#define LFX_NANITE_VERTEX(i) texelFetch(NaniteShadingVertices, i)
#define LFX_NANITE_INTERPOLANT(i) texelFetch(NaniteVertexInterpolants, i)
#endif
#moj_import <lumenfx:nanite/instance_data.glsl>

layout(std140) uniform NaniteInterpolantLayout { ivec4 NaniteInterpolantInfo; };

struct LfxNaniteBarycentrics {
    vec3 value;
    vec3 dx;
    vec3 dy;
};

#if LFX_NANITE_SOFTWARE_RASTER
uint lfxNaniteSoftwareTriangle;
LfxNaniteBarycentrics lfxNaniteSoftwareBarycentrics;
float lfxNaniteSoftwareTwoSidedSign;
#endif

struct LfxNaniteVertex {
    vec3 position;
    vec3 previous;
    vec3 normal;
    vec3 tangent;
    float tangentSign;
    vec4 color;
    vec4 uv01;
    vec4 uv23;
    vec3 localPosition;
};

struct LfxNaniteMaterialGeometry {
    uint instanceId;
    uint materialSlot;
    uint triangleIndex;
    vec3 barycentrics;
    vec3 barycentricsDx;
    vec3 barycentricsDy;
    vec3 position;
    vec3 positionDx;
    vec3 positionDy;
    vec3 previous;
    vec3 localPosition;
    vec3 normal;
    vec3 tangent;
    float tangentSign;
    float twoSidedSign;
    float instanceDeterminantSign;
    float tangentSignDx;
    float tangentSignDy;
    vec4 color;
    vec4 colorDx;
    vec4 colorDy;
    vec4 uv01;
    vec4 uv23;
    vec4 uv01Dx;
    vec4 uv01Dy;
    vec4 uv23Dx;
    vec4 uv23Dy;
    float deviceDepth;
    float surfaceDepth;
    float inverseClipW;
};

LfxNaniteVertex lfx_nanite_vertex(uint index, int corner) {
    int offset = NaniteShadingOffsets.z + (int(index) * 3 + corner) * 9;
    LfxNaniteVertex vertex;
    vertex.position = LFX_NANITE_VERTEX(offset).xyz;
    vertex.previous = LFX_NANITE_VERTEX(offset + 1).xyz;
    vec4 normal = LFX_NANITE_VERTEX(offset + 2);
    vertex.normal = normal.xyz;
    vertex.tangentSign = normal.w;
    vertex.tangent = LFX_NANITE_VERTEX(offset + 3).xyz;
    vertex.color = LFX_NANITE_VERTEX(offset + 4);
    vertex.uv01 = LFX_NANITE_VERTEX(offset + 5);
    vertex.uv23 = LFX_NANITE_VERTEX(offset + 6);
    vertex.localPosition = LFX_NANITE_VERTEX(offset + 7).xyz;
    return vertex;
}

bool lfx_nanite_barycentrics(vec4 a, vec4 b, vec4 c, vec2 pixelCenter, out LfxNaniteBarycentrics weights) {
    vec2 pixelToClip = 2.0 * NaniteShadingViewport.xy * vec2(1.0, NaniteShadingViewport.z);
    vec3 samplePosition = vec3(pixelCenter * pixelToClip - vec2(1.0, NaniteShadingViewport.z), 1.0);
    vec3 edgeA = cross(b.xyw, c.xyw);
    vec3 edgeB = cross(c.xyw, a.xyw);
    vec3 edgeC = cross(a.xyw, b.xyw);
    vec3 g = vec3(dot(edgeA, samplePosition), dot(edgeB, samplePosition), dot(edgeC, samplePosition));
    vec3 gDx = vec3(edgeA.x, edgeB.x, edgeC.x) * pixelToClip.x;
    vec3 gDy = vec3(edgeA.y, edgeB.y, edgeC.y) * pixelToClip.y;
    float sum = g.x + g.y + g.z;
    if (sum == 0.0) return false;

    // The homogeneous edge equations cancel the shared product of the clip W values.
    float inverse = 1.0 / sum;
    weights.value = g * inverse;
    weights.dx = (gDx - weights.value * (gDx.x + gDx.y + gDx.z)) * inverse;
    weights.dy = (gDy - weights.value * (gDy.x + gDy.y + gDy.z)) * inverse;
    return true;
}

vec3 lfx_nanite_interpolate(vec3 weights, vec3 a, vec3 b, vec3 c) {
    return a * weights.x + b * weights.y + c * weights.z;
}

vec4 lfx_nanite_interpolate(vec3 weights, vec4 a, vec4 b, vec4 c) {
    return a * weights.x + b * weights.y + c * weights.z;
}

vec4 lfx_nanite_vertex_interpolant(LfxNaniteMaterialGeometry geometry, int word, out vec4 dx, out vec4 dy) {
    int first = NaniteInterpolantInfo.x + int(geometry.triangleIndex * 3u) * NaniteInterpolantInfo.y + word;
    vec4 a = LFX_NANITE_INTERPOLANT(first);
    vec4 b = LFX_NANITE_INTERPOLANT(first + NaniteInterpolantInfo.y);
    vec4 c = LFX_NANITE_INTERPOLANT(first + 2 * NaniteInterpolantInfo.y);
    dx = lfx_nanite_interpolate(geometry.barycentricsDx, a, b, c);
    dy = lfx_nanite_interpolate(geometry.barycentricsDy, a, b, c);
    return lfx_nanite_interpolate(geometry.barycentrics, a, b, c);
}

bool lfx_nanite_material_geometry(out LfxNaniteMaterialGeometry geometry) {
#if LFX_NANITE_SOFTWARE_RASTER
    uint index = lfxNaniteSoftwareTriangle;
#else
    ivec2 pixel = ivec2(gl_FragCoord.xy);
#if LFX_NANITE_RASTER_MATERIAL
    if (!gl_FrontFacing && (primitiveData.y & 1u) == 0u) return false;
    uint packedValue = primitiveData.x;
#else
    uint packedValue = texelFetch(NaniteVisiblePrimitives, pixel, 0).r;
#endif
    if (packedValue == 0u) return false;
    uint cluster = (packedValue >> 8u) - 1u;
    uint triangle = packedValue & 255u;
    uint index = texelFetch(NanitePrimitiveLookup, NaniteShadingOffsets.x + int(cluster * 128u + triangle)).r;
#endif
    uvec4 primitive = LFX_NANITE_PRIMITIVE(NaniteShadingOffsets.y + int(index));
    if (primitive.y != uint(NaniteShadingOffsets.w)) return false;

    LfxNaniteVertex a = lfx_nanite_vertex(index, 0);
    LfxNaniteVertex b = lfx_nanite_vertex(index, 1);
    LfxNaniteVertex c = lfx_nanite_vertex(index, 2);
    vec4 clipA = NaniteCurrentClip * vec4(a.position, 1.0);
    vec4 clipB = NaniteCurrentClip * vec4(b.position, 1.0);
    vec4 clipC = NaniteCurrentClip * vec4(c.position, 1.0);
#if LFX_NANITE_SOFTWARE_RASTER
    LfxNaniteBarycentrics weights = lfxNaniteSoftwareBarycentrics;
#else
    LfxNaniteBarycentrics weights;
    if (!lfx_nanite_barycentrics(clipA, clipB, clipC, gl_FragCoord.xy, weights)) return false;
#endif

    geometry.instanceId = primitive.x;
    geometry.materialSlot = primitive.y;
    geometry.triangleIndex = index;
    geometry.barycentrics = weights.value;
    geometry.barycentricsDx = weights.dx;
    geometry.barycentricsDy = weights.dy;
    geometry.position = lfx_nanite_interpolate(weights.value, a.position, b.position, c.position);
    geometry.positionDx = lfx_nanite_interpolate(weights.dx, a.position, b.position, c.position);
    geometry.positionDy = lfx_nanite_interpolate(weights.dy, a.position, b.position, c.position) * -NaniteShadingViewport.z;
    geometry.previous = lfx_nanite_interpolate(weights.value, a.previous, b.previous, c.previous);
    geometry.localPosition = lfx_nanite_interpolate(weights.value, a.localPosition, b.localPosition, c.localPosition);
    geometry.normal = lfx_nanite_interpolate(weights.value, a.normal, b.normal, c.normal);
    geometry.tangent = lfx_nanite_interpolate(weights.value, a.tangent, b.tangent, c.tangent);
    geometry.tangentSign = dot(weights.value, vec3(a.tangentSign, b.tangentSign, c.tangentSign));
    geometry.tangentSignDx = dot(weights.dx, vec3(a.tangentSign, b.tangentSign, c.tangentSign));
    geometry.tangentSignDy = dot(weights.dy, vec3(a.tangentSign, b.tangentSign, c.tangentSign)) * -NaniteShadingViewport.z;
    // UE's screen-space Y derivative increases downwards, independent of the host texture origin.
#if LFX_NANITE_SOFTWARE_RASTER
    geometry.twoSidedSign = lfxNaniteSoftwareTwoSidedSign;
#elif LFX_NANITE_RASTER_MATERIAL
    geometry.twoSidedSign = gl_FrontFacing ? 1.0 : -1.0;
#else
    float signTest = (weights.dx.y * weights.dy.x - weights.dx.x * weights.dy.y) * -NaniteShadingViewport.z;
    geometry.twoSidedSign = signTest < 0.0 ? -1.0 : 1.0;
#endif
    geometry.instanceDeterminantSign = (primitive.w & 1u) != 0u ? -1.0 : 1.0;
    geometry.color = lfx_nanite_interpolate(weights.value, a.color, b.color, c.color);
    geometry.colorDx = lfx_nanite_interpolate(weights.dx, a.color, b.color, c.color);
    geometry.colorDy = lfx_nanite_interpolate(weights.dy, a.color, b.color, c.color) * -NaniteShadingViewport.z;
    geometry.uv01 = lfx_nanite_interpolate(weights.value, a.uv01, b.uv01, c.uv01);
    geometry.uv23 = lfx_nanite_interpolate(weights.value, a.uv23, b.uv23, c.uv23);
    geometry.uv01Dx = lfx_nanite_interpolate(weights.dx, a.uv01, b.uv01, c.uv01);
    geometry.uv01Dy = lfx_nanite_interpolate(weights.dy, a.uv01, b.uv01, c.uv01) * -NaniteShadingViewport.z;
    geometry.uv23Dx = lfx_nanite_interpolate(weights.dx, a.uv23, b.uv23, c.uv23);
    geometry.uv23Dy = lfx_nanite_interpolate(weights.dy, a.uv23, b.uv23, c.uv23) * -NaniteShadingViewport.z;
    geometry.inverseClipW = 1.0 / dot(weights.value, vec3(clipA.w, clipB.w, clipC.w));
#if LFX_NANITE_RASTER_MATERIAL
    float rasterDepth = gl_FragCoord.z;
#if !LFX_NANITE_SOFTWARE_RASTER
    if ((primitiveData.y & 8u) != 0u) {
        if (lfxNaniteUnclippedDepth < 0.0) return false;
        rasterDepth = min(lfxNaniteUnclippedDepth, 1.0);
    }
#endif
    geometry.deviceDepth = geometry.surfaceDepth = rasterDepth;
#else
    float originalNdc = dot(weights.value, vec3(clipA.z, clipB.z, clipC.z)) * geometry.inverseClipW;
    geometry.surfaceDepth = NaniteShadingViewport.w > 0.5 ? originalNdc : originalNdc * 0.5 + 0.5;
    geometry.deviceDepth = texelFetch(NaniteVisibleDepth, pixel, 0).r;
#endif
    return true;
}

#endif
