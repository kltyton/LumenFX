using System.Numerics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal readonly record struct MeshBoundVector(double X, double Y, double Z)
{
    internal MeshBoundVector(Vector3 value) : this(value.X, value.Y, value.Z) { }
    public static MeshBoundVector operator +(MeshBoundVector first, MeshBoundVector second) => new(first.X + second.X, first.Y + second.Y, first.Z + second.Z);
    public static MeshBoundVector operator -(MeshBoundVector first, MeshBoundVector second) => new(first.X - second.X, first.Y - second.Y, first.Z - second.Z);
    public static MeshBoundVector operator *(MeshBoundVector value, double scale) => new(value.X * scale, value.Y * scale, value.Z * scale);
    internal double Length() => Math.Sqrt(X * X + Y * Y + Z * Z);
}

internal readonly record struct MeshSourceBounds(MeshBoundVector Origin, MeshBoundVector Extent, double Radius)
{
    internal MeshSourceBounds(Vector3 origin, Vector3 extent, float radius) : this(new MeshBoundVector(origin), new MeshBoundVector(extent), radius) { }

    internal MeshSourceBounds Union(MeshSourceBounds other)
    {
        MeshBoundVector a = Origin - Extent, b = Origin + Extent, c = other.Origin - other.Extent, d = other.Origin + other.Extent;
        var minimum = new MeshBoundVector(Math.Min(a.X, c.X), Math.Min(a.Y, c.Y), Math.Min(a.Z, c.Z));
        var maximum = new MeshBoundVector(Math.Max(b.X, d.X), Math.Max(b.Y, d.Y), Math.Max(b.Z, d.Z));
        MeshBoundVector origin = (minimum + maximum) * .5, extent = (maximum - minimum) * .5;
        double radius = Math.Min(extent.Length(), Math.Max((Origin - origin).Length() + Radius, (other.Origin - origin).Length() + other.Radius));
        return new MeshSourceBounds(origin, extent, radius);
    }

    internal static MeshSourceBounds From(StaticMeshGeometry geometry)
    {
        int count = geometry.SourceInstanceIds.Length;
        if (count == 0) return default;
        Vector3 minimum = MeshTriangleFrames.Vector(geometry.Positions, 0), maximum = minimum;
        for (int vertex = 1; vertex < count; vertex++)
        {
            Vector3 position = MeshTriangleFrames.Vector(geometry.Positions, vertex);
            minimum = Vector3.Min(minimum, position); maximum = Vector3.Max(maximum, position);
        }
        Vector3 center = (minimum + maximum) * 0.5f;
        float square = 0;
        for (int vertex = 0; vertex < count; vertex++)
            square = MathF.Max(square, TriangleTangentBasis.Square(MeshTriangleFrames.Vector(geometry.Positions, vertex) - center));
        return new MeshSourceBounds(center, (maximum - minimum) * 0.5f, MathF.Sqrt(square));
    }
}
