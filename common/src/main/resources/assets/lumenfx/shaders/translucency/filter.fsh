#version 330
#moj_import <lumenfx:lighting/translucency_volume_grid.glsl>

uniform sampler2D TlvUnfilteredAmbient;
uniform sampler2D TlvUnfilteredDirection;
layout(location = 0) out vec4 TlvAmbientOutput;
layout(location = 1) out vec4 TlvDirectionOutput;

void main() {
    ivec3 voxel = lfx_tlv_voxel(ivec2(gl_FragCoord.xy));
    TlvAmbientOutput = vec4(0.0);
    TlvDirectionOutput = vec4(0.0);
    if (voxel.z >= TlvAtlas.x) return;
    for (int corner = 0; corner < 8; corner++) {
        vec3 offset = vec3(corner & 1, (corner >> 1) & 1, (corner >> 2) & 1);
        vec3 uv = (vec3(voxel) + offset) / float(TlvAtlas.x);
        TlvAmbientOutput += lfx_tlv_sample(TlvUnfilteredAmbient, uv);
        TlvDirectionOutput += lfx_tlv_sample(TlvUnfilteredDirection, uv);
    }
    TlvAmbientOutput *= 0.125;
    TlvDirectionOutput *= 0.125;
}
