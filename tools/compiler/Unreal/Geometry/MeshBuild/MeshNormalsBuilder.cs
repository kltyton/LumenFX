using System.Numerics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal readonly record struct MeshNormalsOptions(bool RecomputeNormals, bool RecomputeTangents,
    bool Weighted, bool UseMikk, bool RemoveDegenerates)
{
    internal static MeshNormalsOptions Read(JObject settings) => new(
        settings.Value<bool?>("bRecomputeNormals") ?? true, settings.Value<bool?>("bRecomputeTangents") ?? true,
        settings.Value<bool?>("bComputeWeightedNormals") ?? false, settings.Value<bool?>("bUseMikkTSpace") ?? true,
        settings.Value<bool?>("bRemoveDegenerates") ?? true);
}

internal static class MeshNormalsBuilder
{
    internal static StaticMeshGeometry Build(StaticMeshGeometry source, MeshTopology topology, MeshNormalsOptions options,
        NativeMikkTangents? mikk)
    {
        if (topology.Triangles.Count == 0) return source;
        bool forceTangents = options.RecomputeNormals || options.RecomputeTangents;
        bool useMikk = forceTangents && options.UseMikk && source.TextureCoordinates.Count != 0;
        if (useMikk && mikk is null) throw new ConversionException("Mesh build requires the matching MikkTSpace source tool");
        float[] normals = (float[])source.Normals.Clone(), tangents = (float[])source.Tangents.Clone(),
            signs = (float[])source.BinormalSigns.Clone();
        if (options.RecomputeNormals) Array.Clear(normals);
        if (forceTangents) { Array.Clear(tangents); Array.Clear(signs); }
        MeshTriangleFrames frames = MeshTriangleFrames.Compute(source, topology, options.Weighted, options.RemoveDegenerates);
        foreach (int vertex in topology.Vertices)
        {
            var neighborhood = MeshNormalGroups.Neighborhood(topology, vertex);
            if (ValidBasis(neighborhood.Values, normals, tangents, signs)) continue;
            foreach (var group in MeshNormalGroups.Connected(topology, neighborhood))
                ApplyGroup(source, topology, group, frames, normals, tangents, signs, useMikk);
        }
        StaticMeshGeometry result = source with { Normals = normals, Tangents = tangents, BinormalSigns = signs };
        return useMikk ? mikk!.Generate(result, options.RemoveDegenerates) : result;
    }

    private static bool ValidBasis(IEnumerable<MeshNormalCorner> corners, float[] normals, float[] tangents, float[] signs)
    {
        foreach (MeshNormalCorner corner in corners)
        {
            Vector3 normal = MeshTriangleFrames.Vector(normals, corner.Instance), tangent = MeshTriangleFrames.Vector(tangents, corner.Instance);
            if (TriangleTangentBasis.NearlyZero(normal) || TriangleTangentBasis.NearlyZero(tangent)) return false;
            Vector3 x = TriangleTangentBasis.SafeNormal(tangent), z = TriangleTangentBasis.SafeNormal(normal);
            Vector3 y = TriangleTangentBasis.SafeNormal(TriangleTangentBasis.SafeNormal(Vector3.Cross(z, x)) * signs[corner.Instance]);
            if (!TriangleTangentBasis.Finite(x) || !TriangleTangentBasis.Finite(y) || !TriangleTangentBasis.Finite(z)
                    || TriangleTangentBasis.NearlyZero(x, 1.0e-8f) || TriangleTangentBasis.NearlyZero(y, 1.0e-8f)
                    || TriangleTangentBasis.NearlyZero(z, 1.0e-8f)) return false;
        }
        return true;
    }

    private static void ApplyGroup(StaticMeshGeometry source, MeshTopology topology, IReadOnlyList<MeshNormalCorner> group,
        MeshTriangleFrames frames, float[] normals, float[] tangents, float[] signs, bool useMikk)
    {
        Vector3 groupNormal = Vector3.Zero;
        var groupTangents = new Dictionary<Vector2, Vector3>();
        var groupBinormals = new Dictionary<Vector2, Vector3>();
        foreach (MeshNormalCorner corner in group)
        {
            MeshTriangle triangle = topology.Triangles[corner.Triangle];
            int triangleCorner = Array.IndexOf(triangle.Instances, corner.Instance);
            float weight = frames.Weights[corner.Triangle][triangleCorner];
            TriangleTangentBasis basis = frames.Bases[corner.Triangle];
            Vector3 normal = basis.Normal * weight;
            if (!TriangleTangentBasis.NearlyZero(normal, 1.0e-8f) && TriangleTangentBasis.Finite(normal)) groupNormal += normal;
            if (useMikk) continue;
            Vector2 uv = Uv(source, corner.Instance);
            if (!groupTangents.ContainsKey(uv)) { groupTangents.Add(uv, Vector3.Zero); groupBinormals.Add(uv, Vector3.Zero); }
            Vector3 tangent = basis.Tangent * weight, binormal = basis.Binormal * weight;
            if (!TriangleTangentBasis.NearlyZero(tangent, 1.0e-8f) && TriangleTangentBasis.Finite(tangent)) groupTangents[uv] += tangent;
            if (!TriangleTangentBasis.NearlyZero(binormal, 1.0e-8f) && TriangleTangentBasis.Finite(binormal)) groupBinormals[uv] += binormal;
        }
        TriangleTangentBasis.Normalize(ref groupNormal);
        if (!useMikk)
        {
            foreach (Vector2 uv in groupTangents.Keys.ToArray())
            {
                Vector3 tangent = groupTangents[uv], binormal = groupBinormals[uv];
                TriangleTangentBasis.Normalize(ref tangent); TriangleTangentBasis.Normalize(ref binormal);
                groupTangents[uv] = tangent; groupBinormals[uv] = binormal;
            }
        }
        foreach (MeshNormalCorner corner in group)
        {
            int instance = corner.Instance;
            Vector3 normal = MeshTriangleFrames.Vector(normals, instance);
            if (TriangleTangentBasis.NearlyZero(normal, 1.0e-8f)) { normal = groupNormal; MeshTriangleFrames.Set(normals, instance, normal); }
            if (useMikk) continue;
            Vector2 uv = Uv(source, instance);
            Vector3 tangent = MeshTriangleFrames.Vector(tangents, instance);
            Vector3 selectedTangent = TriangleTangentBasis.NearlyZero(tangent, 1.0e-8f) ? groupTangents[uv] : tangent;
            Vector3 selectedBinormal = groupBinormals[uv];
            if (!TriangleTangentBasis.NearlyZero(normal, 1.0e-8f) && !TriangleTangentBasis.NearlyZero(tangent, 1.0e-8f))
            {
                Vector3 binormal = TriangleTangentBasis.SafeNormal(Vector3.Cross(normal, tangent)) * signs[instance];
                if (!TriangleTangentBasis.NearlyZero(binormal, 1.0e-8f)) selectedBinormal = binormal;
            }
            selectedBinormal -= selectedTangent * TriangleTangentBasis.Dot(selectedTangent, selectedBinormal);
            TriangleTangentBasis.Normalize(ref selectedBinormal);
            selectedTangent -= normal * TriangleTangentBasis.Dot(normal, selectedTangent);
            TriangleTangentBasis.Normalize(ref selectedTangent);
            selectedBinormal -= normal * TriangleTangentBasis.Dot(normal, selectedBinormal);
            TriangleTangentBasis.Normalize(ref selectedBinormal);
            MeshTriangleFrames.Set(tangents, instance, selectedTangent);
            signs[instance] = TriangleTangentBasis.Dot(selectedTangent, Vector3.Cross(selectedBinormal, normal)) < 0 ? -1 : 1;
        }
    }

    private static Vector2 Uv(StaticMeshGeometry geometry, int instance) => geometry.TextureCoordinates.Count == 0
        ? Vector2.Zero : MeshTriangleFrames.Coordinate(geometry.TextureCoordinates[0], instance);
}
