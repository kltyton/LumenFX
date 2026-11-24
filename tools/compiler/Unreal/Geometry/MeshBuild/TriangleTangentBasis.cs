using System.Numerics;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal readonly record struct TriangleTangentBasis(Vector3 Normal, Vector3 Tangent, Vector3 Binormal)
{
    internal static TriangleTangentBasis WithUv(Vector3 first, Vector3 second, Vector3 third,
        Vector2 firstUv, Vector2 secondUv, Vector2 thirdUv, float comparisonThreshold)
    {
        Vector3 edge1 = second - first, edge2 = third - first;
        Vector2 uv1 = secondUv - firstUv, uv2 = thirdUv - firstUv;
        float tolerance = MathF.Max(comparisonThreshold * comparisonThreshold, 1.17549435e-38f);
        Vector3 normal = SafeNormal(Vector3.Cross(edge2, edge1), tolerance);
        if (NearlyZero(normal, comparisonThreshold)) return default;
        float determinant = uv1.X * uv2.Y - uv1.Y * uv2.X;
        // A singular texture-to-parameter matrix uses the identity inverse in the source path.
        Vector3 tangent = SafeNormal(determinant == 0 ? edge1 : (edge1 * uv2.Y - edge2 * uv1.Y) / determinant);
        Vector3 binormal = SafeNormal(determinant == 0 ? edge2 : (edge2 * uv1.X - edge1 * uv2.X) / determinant);
        float normalSquare = Square(normal);
        tangent -= normal * (Dot(tangent, normal) / normalSquare);
        binormal -= normal * (Dot(binormal, normal) / normalSquare);
        if (Square(tangent) < 0.00001f * 0.00001f) tangent = Vector3.Cross(binormal, normal);
        if (Square(binormal) < 0.00001f * 0.00001f) binormal = Vector3.Cross(tangent, normal);
        Normalize(ref tangent);
        Normalize(ref binormal);
        Normalize(ref normal);
        if (NearlyZero(tangent) || !Finite(tangent) || NearlyZero(binormal) || !Finite(binormal))
            tangent = binormal = Vector3.Zero;
        if (NearlyZero(normal) || !Finite(normal)) normal = Vector3.Zero;
        return new TriangleTangentBasis(SafeNormal(normal), SafeNormal(tangent), SafeNormal(binormal));
    }

    internal static TriangleTangentBasis WithoutUv(Vector3 first, Vector3 second, Vector3 third, float comparisonThreshold)
    {
        float tolerance = MathF.Max(comparisonThreshold * comparisonThreshold, 1.17549435e-38f);
        Vector3 normal = SafeNormal(Vector3.Cross(third - first, second - first), tolerance);
        if (!Normalize(ref normal, comparisonThreshold)) return default;
        Vector3 tangent, binormal;
        if (normal.Z < 0)
        {
            float a = 1 / (1 - normal.Z), b = normal.X * normal.Y * a;
            tangent = new Vector3(1 - normal.X * normal.X * a, -b, normal.X);
            binormal = new Vector3(b, normal.Y * normal.Y * a - 1, -normal.Y);
        }
        else
        {
            float a = 1 / (1 + normal.Z), b = -normal.X * normal.Y * a;
            tangent = new Vector3(1 - normal.X * normal.X * a, b, -normal.X);
            binormal = new Vector3(b, 1 - normal.Y * normal.Y * a, -normal.Y);
        }
        return new TriangleTangentBasis(normal, tangent, binormal);
    }

    internal static float Dot(Vector3 first, Vector3 second) => first.X * second.X + first.Y * second.Y + first.Z * second.Z;
    internal static float Square(Vector3 value) => Dot(value, value);
    internal static bool NearlyZero(Vector3 value, float tolerance = 1.0e-4f) => MathF.Abs(value.X) <= tolerance
        && MathF.Abs(value.Y) <= tolerance && MathF.Abs(value.Z) <= tolerance;
    internal static bool Finite(Vector3 value) => float.IsFinite(value.X) && float.IsFinite(value.Y) && float.IsFinite(value.Z);

    internal static Vector3 SafeNormal(Vector3 value, float tolerance = 1.0e-8f)
    {
        float square = Square(value);
        if (square == 1) return value;
        return square < tolerance ? Vector3.Zero : value * (1 / MathF.Sqrt(square));
    }

    internal static bool Normalize(ref Vector3 value, float tolerance = 1.0e-8f)
    {
        float square = Square(value);
        if (!(square > tolerance)) return false;
        value *= 1 / MathF.Sqrt(square);
        return true;
    }
}
