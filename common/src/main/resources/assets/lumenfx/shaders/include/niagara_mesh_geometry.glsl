#ifndef LFX_NIAGARA_MESH_GEOMETRY
#define LFX_NIAGARA_MESH_GEOMETRY

layout(std140) uniform NiagaraMeshInfo {
    uvec4 MeshLayout;
};
#if LFX_DYNAMIC_MESH
uniform usamplerBuffer NiagaraMeshVertices;
#else
uniform samplerBuffer NiagaraMeshVertices;
#endif

struct LfxMeshVertex {
    vec3 position;
    vec3 normal;
    vec3 tangent;
    float binormalSign;
    vec4 color;
    int sourceInstanceId;
};

#if LFX_DYNAMIC_MESH
uint lfx_mesh_word(uint address) {
    return texelFetch(NiagaraMeshVertices, int(address)).r;
}
vec4 lfx_mesh_snorm(uint packedValue) {
    ivec4 bytes = ivec4(packedValue << 24, packedValue << 16, packedValue << 8, packedValue) >> 24;
    return max(vec4(bytes) / 127.0, vec4(-1.0));
}
LfxMeshVertex lfx_mesh_vertex(int vertexId) {
    uint vertexIndex = uint(vertexId);
    uint position = vertexIndex * 3u;
    LfxMeshVertex vertex;
    vertex.position = uintBitsToFloat(uvec3(lfx_mesh_word(position), lfx_mesh_word(position + 1u), lfx_mesh_word(position + 2u)));
    vertex.normal = vec3(0.0, 0.0, 1.0);
    vertex.tangent = vec3(1.0, 0.0, 0.0);
    vertex.binormalSign = 1.0;
    if (MeshLayout.x != 0xffffffffu) {
        uint tangent = MeshLayout.x + vertexIndex * 2u;
        vertex.tangent = lfx_mesh_snorm(lfx_mesh_word(tangent)).xyz;
        vec4 normal = lfx_mesh_snorm(lfx_mesh_word(tangent + 1u));
        vertex.normal = normal.xyz;
        vertex.binormalSign = normal.w;
    }
    vertex.color = vec4(1.0);
    if (MeshLayout.z != 0xffffffffu) {
        uint bgra = lfx_mesh_word(MeshLayout.z + vertexIndex);
        vertex.color = vec4(uvec4(bgra >> 16u, bgra >> 8u, bgra, bgra >> 24u) & 255u) / 255.0;
    }
    vertex.sourceInstanceId = vertexId;
    return vertex;
}

vec4 lfx_mesh_uv4(int vertexId, uint channel) {
    if (MeshLayout.w == 0u) return vec4(0.0);
    uint address = MeshLayout.y + (uint(vertexId) * MeshLayout.w + min(channel, MeshLayout.w - 1u)) * 2u;
    return vec4(uintBitsToFloat(uvec2(lfx_mesh_word(address), lfx_mesh_word(address + 1u))), 0.0, 0.0);
}

vec2 lfx_mesh_uv(int vertexId, uint channel) {
    return lfx_mesh_uv4(vertexId, channel).xy;
}
#else
LfxMeshVertex lfx_mesh_vertex(int vertexId) {
    int base = vertexId * int(MeshLayout.x);
    vec4 positionSign = texelFetch(NiagaraMeshVertices, base);
    vec4 normalId = texelFetch(NiagaraMeshVertices, base + 1);
    vec4 tangentId = texelFetch(NiagaraMeshVertices, base + 2);
    LfxMeshVertex vertex;
    vertex.position = positionSign.xyz;
    vertex.binormalSign = positionSign.w;
    vertex.normal = normalId.xyz;
    vertex.sourceInstanceId = int(uint(normalId.w) | (uint(tangentId.w) << 16));
    vertex.tangent = tangentId.xyz;
    vertex.color = texelFetch(NiagaraMeshVertices, base + 3);
    return vertex;
}

vec4 lfx_mesh_uv4(int vertexId, uint channel) {
    if (MeshLayout.y == 0u) return vec4(0.0);
    uint clampedChannel = min(channel, MeshLayout.y - 1u);
    if (MeshLayout.z != 0u) {
        return texelFetch(NiagaraMeshVertices, vertexId * int(MeshLayout.x) + 4 + int(clampedChannel));
    }
    int address = vertexId * int(MeshLayout.x) + 4 + int(clampedChannel / 2u);
    vec4 coordinates = texelFetch(NiagaraMeshVertices, address);
    return vec4((clampedChannel & 1u) == 0u ? coordinates.xy : coordinates.zw, 0.0, 0.0);
}

vec2 lfx_mesh_uv(int vertexId, uint channel) {
    return lfx_mesh_uv4(vertexId, channel).xy;
}
#endif

mat3 lfx_mesh_tangent_basis(LfxMeshVertex vertex, mat3 unscaledRotation) {
    vec3 bitangent = cross(vertex.normal, vertex.tangent) * vertex.binormalSign;
    vec3 tangent = cross(bitangent, vertex.normal) * vertex.binormalSign;
    return mat3(unscaledRotation * tangent, unscaledRotation * bitangent, unscaledRotation * vertex.normal);
}

mat3 lfx_unity_mesh_tangent_basis(LfxMeshVertex vertex, mat3 linear, mat3 rotation, float determinantSign) {
    // The cofactor gives the inverse-transpose direction without dividing by a possibly zero size.
    mat3 cofactor = mat3(cross(linear[1], linear[2]), cross(linear[2], linear[0]), cross(linear[0], linear[1]));
    vec3 normal = cofactor * vertex.normal * determinantSign;
    normal = dot(normal, normal) > 0.0 ? normalize(normal) : rotation * vertex.normal;
    vec3 tangent = linear * vertex.tangent;
    tangent -= normal * dot(tangent, normal);
    tangent = dot(tangent, tangent) > 0.0 ? normalize(tangent) : rotation * vertex.tangent;
    return mat3(tangent, cross(normal, tangent) * vertex.binormalSign * determinantSign, normal);
}

#endif
