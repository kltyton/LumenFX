using System.Numerics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record MeshScaledGeometry(StaticMeshGeometry Geometry, float[] Binormals);

internal static class MeshVertexScale
{
    internal static StaticMeshGeometry Apply(StaticMeshGeometry source, Vector3 scale, bool needTangents, bool legacy)
        => Prepare(source, scale, needTangents, legacy).Geometry;

    internal static MeshScaledGeometry Prepare(StaticMeshGeometry source, Vector3 scale, bool needTangents, bool legacy)
    {
        float[] positions = new float[source.Positions.Length], normals = new float[source.Normals.Length],
            tangents = new float[source.Tangents.Length], binormals = new float[source.Tangents.Length],
            signs = new float[source.BinormalSigns.Length];
        for (int vertex = 0; vertex < signs.Length; vertex++)
        {
            Vector3 position = MeshTriangleFrames.Vector(source.Positions, vertex) * scale;
            Vector3 normal = MeshTriangleFrames.Vector(source.Normals, vertex);
            Vector3 tangent = MeshTriangleFrames.Vector(source.Tangents, vertex);
            Vector3 binormal = Vector3.Cross(normal, tangent) * source.BinormalSigns[vertex];
            if (needTangents)
            {
                tangent = legacy ? tangent / scale : tangent * scale;
                binormal = legacy ? binormal / scale : binormal * scale;
                TriangleTangentBasis.Normalize(ref tangent);
                TriangleTangentBasis.Normalize(ref binormal);
            }
            else { tangent = Vector3.UnitX; binormal = Vector3.UnitY; }
            normal /= scale;
            TriangleTangentBasis.Normalize(ref normal);
            MeshTriangleFrames.Set(positions, vertex, position);
            MeshTriangleFrames.Set(normals, vertex, normal);
            MeshTriangleFrames.Set(tangents, vertex, tangent);
            MeshTriangleFrames.Set(binormals, vertex, binormal);
            // SetTangents computes this determinant after promoting its basis vectors to double.
            double determinant = (double)tangent.X * ((double)binormal.Y * normal.Z - (double)binormal.Z * normal.Y)
                - (double)tangent.Y * ((double)binormal.X * normal.Z - (double)binormal.Z * normal.X)
                + (double)tangent.Z * ((double)binormal.X * normal.Y - (double)binormal.Y * normal.X);
            signs[vertex] = determinant < 0 ? -1 : 1;
        }
        return new MeshScaledGeometry(source with { Positions = positions, Normals = normals, Tangents = tangents,
            BinormalSigns = signs }, binormals);
    }
}
