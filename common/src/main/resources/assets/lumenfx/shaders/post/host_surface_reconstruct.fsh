#version 330

layout(std140) uniform HostSurfaceReconstructView {
    mat4 HostInverseProjection;
};

uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;

layout(location = 0) out vec4 BaseDepthOutput;
layout(location = 1) out vec4 GeometryOutput;
layout(location = 2) out vec4 MaterialOutput;

vec3 lfx_reconstruct_view_pos(ivec2 pixel, ivec2 size, float depth) {
    vec2 ndc = (vec2(pixel) + 0.5) / vec2(size) * 2.0 - 1.0;
    float logicalZ = 1.0 - 2.0 * depth;
    vec4 view = HostInverseProjection * vec4(ndc, logicalZ, 1.0);
    return view.xyz / view.w;
}

vec2 lfx_reconstruct_octahedral(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 p = n.xy;
    if (n.z < 0.0) p = (1.0 - abs(p.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), p));
    return p;
}

void main() {
    ivec2 size = textureSize(SceneDepth, 0);
    ivec2 pixel = clamp(ivec2(gl_FragCoord.xy), ivec2(0), size - 1);
    vec4 scene = texelFetch(SceneColor, pixel, 0);
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    if (depth <= 0.0 || depth > 1.0) {
        BaseDepthOutput = vec4(max(scene.rgb, vec3(0.0)), -1.0);
        GeometryOutput = vec4(0.0);
        MaterialOutput = vec4(0.5, -1.0, 0.0, 0.0);
        return;
    }

    vec3 center = lfx_reconstruct_view_pos(pixel, size, depth);
    ivec2 pxL = ivec2(max(pixel.x - 1, 0), pixel.y);
    ivec2 pxR = ivec2(min(pixel.x + 1, size.x - 1), pixel.y);
    ivec2 pxD = ivec2(pixel.x, max(pixel.y - 1, 0));
    ivec2 pxU = ivec2(pixel.x, min(pixel.y + 1, size.y - 1));

    float dL = texelFetch(SceneDepth, pxL, 0).r;
    float dR = texelFetch(SceneDepth, pxR, 0).r;
    float dD = texelFetch(SceneDepth, pxD, 0).r;
    float dU = texelFetch(SceneDepth, pxU, 0).r;

    bool validL = pxL.x != pixel.x && dL > 0.0 && dL <= 1.0;
    bool validR = pxR.x != pixel.x && dR > 0.0 && dR <= 1.0;
    bool validD = pxD.y != pixel.y && dD > 0.0 && dD <= 1.0;
    bool validU = pxU.y != pixel.y && dU > 0.0 && dU <= 1.0;

    vec3 dpdx;
    if (validR && (!validL || abs(dR - depth) <= abs(depth - dL))) {
        dpdx = lfx_reconstruct_view_pos(pxR, size, dR) - center;
    } else if (validL) {
        dpdx = center - lfx_reconstruct_view_pos(pxL, size, dL);
    } else {
        dpdx = vec3(1.0, 0.0, 0.0);
    }

    vec3 dpdy;
    if (validU && (!validD || abs(dU - depth) <= abs(depth - dD))) {
        dpdy = lfx_reconstruct_view_pos(pxU, size, dU) - center;
    } else if (validD) {
        dpdy = center - lfx_reconstruct_view_pos(pxD, size, dD);
    } else {
        dpdy = vec3(0.0, 1.0, 0.0);
    }

    vec3 rawNormal = cross(dpdx, dpdy);
    float normalLenSq = dot(rawNormal, rawNormal);
    vec3 viewNormal = normalLenSq > 1.0e-12 ? rawNormal * inversesqrt(normalLenSq) : vec3(0.0, 0.0, 1.0);
    if (viewNormal.z < 0.0) viewNormal = -viewNormal;

    BaseDepthOutput = vec4(max(scene.rgb, vec3(0.0)), depth);
    GeometryOutput = vec4(center.xy, lfx_reconstruct_octahedral(viewNormal));
    MaterialOutput = vec4(0.5, -1.0, 0.0, 0.0);
}
