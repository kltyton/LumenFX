using System.Numerics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record MeshTriangleFrames(TriangleTangentBasis[] Bases, float[][] Weights)
{
    internal static MeshTriangleFrames Compute(StaticMeshGeometry geometry, MeshTopology topology, bool weighted, bool removeDegenerates)
    {
        var bases = new TriangleTangentBasis[topology.Triangles.Count];
        var weights = new float[topology.Triangles.Count][];
        bool hasUv = geometry.TextureCoordinates.Count != 0;
        float threshold = removeDegenerates ? 0.00002f : 0;
        for (int i = 0; i < bases.Length; i++)
        {
            MeshTriangle triangle = topology.Triangles[i];
            Vector3 first = Vector(geometry.Positions, triangle.Instances[0]);
            Vector3 second = Vector(geometry.Positions, triangle.Instances[1]);
            Vector3 third = Vector(geometry.Positions, triangle.Instances[2]);
            bases[i] = hasUv ? TriangleTangentBasis.WithUv(first, second, third,
                Coordinate(geometry.TextureCoordinates[0], triangle.Instances[0]),
                Coordinate(geometry.TextureCoordinates[0], triangle.Instances[1]),
                Coordinate(geometry.TextureCoordinates[0], triangle.Instances[2]), threshold)
                : TriangleTangentBasis.WithoutUv(first, second, third, threshold);
            if (weighted)
            {
                Vector3 cross = Vector3.Cross(second - first, third - first);
                float area = MathF.Sqrt(TriangleTangentBasis.Square(cross)) / 2;
                weights[i] = [area * Angle(first, second, third), area * Angle(second, third, first), area * Angle(third, first, second)];
            }
            else weights[i] = [1, 1, 1];
        }
        return new MeshTriangleFrames(bases, weights);
    }

    private static float Angle(Vector3 first, Vector3 second, Vector3 third)
    {
        Vector3 edge1 = second - first, edge2 = third - first;
        if (!TriangleTangentBasis.Normalize(ref edge1) || !TriangleTangentBasis.Normalize(ref edge2)) return 0;
        float value = TriangleTangentBasis.Dot(edge1, edge2);
        return MathF.Acos(value < -1 ? -1 : value < 1 ? value : 1);
    }

    internal static Vector3 Vector(float[] values, int instance) => new(values[instance * 3], values[instance * 3 + 1], values[instance * 3 + 2]);
    internal static Vector2 Coordinate(float[] values, int instance) => new(values[instance * 2], values[instance * 2 + 1]);
    internal static void Set(float[] values, int instance, Vector3 value)
    {
        values[instance * 3] = value.X; values[instance * 3 + 1] = value.Y; values[instance * 3 + 2] = value.Z;
    }
}
