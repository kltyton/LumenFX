#version 330
#moj_import <lumenfx:lighting/translucency_volume_grid.glsl>

layout(std140) uniform TlvHistory {
    vec4 TlvPreviousMinimum;
    vec4 TlvHistoryWeight;
};
uniform sampler2D TlvUnfilteredAmbient;
uniform sampler2D TlvUnfilteredDirection;
uniform sampler2D TlvHistoryAmbient;
uniform sampler2D TlvHistoryDirection;
layout(location = 0) out vec4 TlvAmbientOutput;
layout(location = 1) out vec4 TlvDirectionOutput;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec3 voxel = lfx_tlv_voxel(pixel);
    TlvAmbientOutput = texelFetch(TlvUnfilteredAmbient, pixel, 0);
    TlvDirectionOutput = texelFetch(TlvUnfilteredDirection, pixel, 0);
    if (voxel.z >= TlvAtlas.x || TlvHistoryWeight.x <= 0.0 || TlvPreviousMinimum.w <= 0.0) return;
    vec4 cascade = TlvCascade[TlvAtlas.w];
    if (cascade.w <= 0.0) return;
    vec3 centre = vec3(voxel) + 0.5;
    vec3 position = cascade.xyz + centre * cascade.w;
    ivec3 previous = ivec3((position - TlvPreviousMinimum.xyz) / TlvPreviousMinimum.w);
    if (any(lessThan(previous, ivec3(0))) || any(greaterThanEqual(previous, ivec3(TlvAtlas.x)))) return;
    float weight = TlvHistoryWeight.x * (TlvAtlas.w == 1 ? lfx_tlv_border(centre) : 1.0);
    ivec2 previousPixel = lfx_tlv_pixel(previous);
    TlvAmbientOutput = mix(TlvAmbientOutput, texelFetch(TlvHistoryAmbient, previousPixel, 0), weight);
    TlvDirectionOutput = mix(TlvDirectionOutput, texelFetch(TlvHistoryDirection, previousPixel, 0), weight);
}
