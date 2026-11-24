#include "MeshRenderVertexExchange.h"
#include "Serialization/Archive.h"
#include "StaticMeshResources.h"

namespace
{
    template<class Vector>
    void WriteVector(FArchive& Ar, const Vector& Value)
    {
        float X = Value.X, Y = Value.Y, Z = Value.Z;
        Ar << X << Y << Z;
    }
}

bool WriteRenderVertexChannels(FArchive& Ar, const FStaticMeshVertexBuffers& Buffers)
{
    const auto& Positions = Buffers.PositionVertexBuffer;
    const auto& Vertices = Buffers.StaticMeshVertexBuffer;
    const auto& Colors = Buffers.ColorVertexBuffer;
    uint32 VertexCount = Positions.GetNumVertices();
    const uint32 ColorCount = Colors.GetNumVertices();
    if (VertexCount != Vertices.GetNumVertices()) return false;
    // A color buffer may retain its vertex count after the CPU allocation was discarded by the RHI.
    if (ColorCount != 0 && (ColorCount != VertexCount || Colors.GetVertexData() == nullptr
        || Colors.GetAllocatedSize() < static_cast<uint64>(ColorCount) * sizeof(FColor))) return false;
    for (uint32 Index = 0; Index < VertexCount; ++Index) WriteVector(Ar, Positions.VertexPosition(Index));
    for (uint32 Index = 0; Index < VertexCount; ++Index) WriteVector(Ar, Vertices.VertexTangentZ(Index));
    for (uint32 Index = 0; Index < VertexCount; ++Index) WriteVector(Ar, Vertices.VertexTangentX(Index));
    for (uint32 Index = 0; Index < VertexCount; ++Index)
    {
        float Sign = Vertices.VertexTangentZ(Index).W;
        Ar << Sign;
    }
    for (uint32 Index = 0; Index < VertexCount; ++Index)
    {
        FColor Color = ColorCount == 0 ? FColor::White : Colors.VertexColor(Index);
        float R = Color.R / 255.0f, G = Color.G / 255.0f, B = Color.B / 255.0f, A = Color.A / 255.0f;
        Ar << R << G << B << A;
    }
    for (uint32 Channel = 0; Channel < Vertices.GetNumTexCoords(); ++Channel)
        for (uint32 Index = 0; Index < VertexCount; ++Index)
        {
            const FVector2f UV = Vertices.GetVertexUV(Index, Channel);
            float U = UV.X, V = UV.Y;
            Ar << U << V;
        }
    return !Ar.IsError();
}
