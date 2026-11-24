#include <algorithm>
#include "mikktspace.h"

namespace {
struct MeshData {
    int faces;
    const int* indices;
    const float* positions;
    const float* normals;
    const float* coordinates;
    float* tangents;
    float* signs;
};

const MeshData& mesh(const SMikkTSpaceContext* context) {
    return *static_cast<const MeshData*>(context->m_pUserData);
}

int vertex(const SMikkTSpaceContext* context, int face, int corner) {
    return mesh(context).indices[face * 3 + corner];
}

int faces(const SMikkTSpaceContext* context) { return mesh(context).faces; }
int corners(const SMikkTSpaceContext*, int) { return 3; }

void position(const SMikkTSpaceContext* context, float* output, int face, int corner) {
    std::copy_n(mesh(context).positions + vertex(context, face, corner) * 3, 3, output);
}

void normal(const SMikkTSpaceContext* context, float* output, int face, int corner) {
    std::copy_n(mesh(context).normals + vertex(context, face, corner) * 3, 3, output);
}

void coordinates(const SMikkTSpaceContext* context, float* output, int face, int corner) {
    std::copy_n(mesh(context).coordinates + vertex(context, face, corner) * 2, 2, output);
}

void tangent(const SMikkTSpaceContext* context, const float* value, float sign, int face, int corner) {
    int index = vertex(context, face, corner);
    std::copy_n(value, 3, mesh(context).tangents + index * 3);
    // Unreal's mesh-description binormal convention reverses MikkTSpace's sign.
    mesh(context).signs[index] = -sign;
}
}

extern "C" __declspec(dllexport) int LumenFxGenerateMeshTangents(int vertexCount, int indexCount,
    const int* indices, const float* positions, const float* normals, const float* uv,
    float* tangents, float* signs, int ignoreDegenerates) {
    if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0
        || !indices || !positions || !normals || !uv || !tangents || !signs) return 1;
    for (int i = 0; i < indexCount; i++)
        if (indices[i] < 0 || indices[i] >= vertexCount) return 1;
    MeshData data{indexCount / 3, indices, positions, normals, uv, tangents, signs};
    SMikkTSpaceInterface callbacks{};
    callbacks.m_getNumFaces = faces;
    callbacks.m_getNumVerticesOfFace = corners;
    callbacks.m_getPosition = position;
    callbacks.m_getNormal = normal;
    callbacks.m_getTexCoord = coordinates;
    callbacks.m_setTSpaceBasic = tangent;
    SMikkTSpaceContext context{&callbacks, &data, ignoreDegenerates != 0};
    return genTangSpaceDefault(&context) ? 0 : 2;
}
