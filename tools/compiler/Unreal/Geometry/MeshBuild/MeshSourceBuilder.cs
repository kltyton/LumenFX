using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using System.Numerics;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshSourceBuilder
{
    internal sealed record BuiltLod(StaticMeshGeometry Geometry, MeshSourceBounds Bounds, int[] WedgeMap,
        InvalidMeshChannels Repaired, float MaximumDeviation = 0, float? PixelError = null, bool? HasVertexColors = null);

    internal static BuiltLod BuildPreparedLod(StaticMeshSourceData asset, PreparedMeshLod source,
        NativeMeshIndexOrder indexOrder, MeshVertexColors colors, bool optimize = true)
    {
        StaticMeshGeometry geometry = source.Source.Geometry ?? throw new ConversionException("Prepared LOD has no geometry");
        MeshRenderGeometry render = BuildRenderIndices(geometry, asset, source.Source, true, colors);
        MeshSourceBounds bounds = MeshSourceBounds.From(render.Geometry);
        MeshRenderGeometry optimized = optimize ? MeshVertexCache.Optimize(render, geometry.SourceInstanceIds.Length, indexOrder) : render;
        return new BuiltLod(optimized.Geometry, bounds, optimized.WedgeMap, InvalidMeshChannels.None,
            source.MaximumDeviation, source.PixelError);
    }

    internal static async Task<BuiltLod> BuildImportedLodAsync(StaticMeshSourceData asset, StaticMeshSourceLod lod,
        NativeMikkTangents mikk, NativeMeshIndexOrder indexOrder, NativeLightmapLayout lightmap, MeshVertexColors colors, bool optimize = true)
    {
        MeshSourceRepair basis = PrepareBasis(lod, mikk);
        MeshTopology topology = MeshTopology.Read(lod.Description!, basis.Geometry);
        StaticMeshGeometry uvGeometry = await MeshLightmapUvs.GenerateAsync(basis.Geometry, topology, asset, lod, lightmap);
        MeshRenderGeometry render = BuildRenderIndices(uvGeometry, asset, lod, true, colors);
        MeshSourceBounds bounds = MeshSourceBounds.From(render.Geometry);
        MeshRenderGeometry optimized = optimize ? MeshVertexCache.Optimize(render, basis.Geometry.SourceInstanceIds.Length, indexOrder) : render;
        return new BuiltLod(optimized.Geometry, bounds, optimized.WedgeMap, basis.Repaired);
    }

    internal static MeshSourceRepair PrepareBasis(StaticMeshSourceLod source, NativeMikkTangents? mikk)
    {
        if (source.Geometry is null || source.Description is null)
            throw new ConversionException($"Source LOD {source.Index} requires its generated or imported geometry before basis preparation");
        MeshSourceRepair repaired = MeshSourceSanitizer.Repair(source.Geometry);
        MeshTopology topology = MeshTopology.Read(source.Description, repaired.Geometry);
        JObject settings = source.Settings["BuildSettings"] as JObject ?? new JObject();
        StaticMeshGeometry geometry = MeshNormalsBuilder.Build(repaired.Geometry, topology, MeshNormalsOptions.Read(settings), mikk);
        return repaired with { Geometry = geometry };
    }

    internal static StaticMeshGeometry PackVertices(StaticMeshGeometry preparedBasis, StaticMeshSourceData asset,
        StaticMeshSourceLod lod, bool needTangents, MeshVertexColors colors)
    {
        JObject settings = lod.Settings["BuildSettings"] as JObject ?? new JObject();
        JToken? vector = settings["BuildScale3D"];
        var scale = new Vector3((float)SourceAsset.Number(vector?["X"], 1), (float)SourceAsset.Number(vector?["Y"], 1),
            (float)SourceAsset.Number(vector?["Z"], 1));
        StaticMeshGeometry scaled = MeshVertexScale.Apply(preparedBasis, scale, needTangents,
            asset.Settings.Value<bool>("legacy_tangent_scaling"));
        return MeshStoragePrecision.Pack(colors.Pack(scaled), settings);
    }

    internal static MeshRenderGeometry BuildRenderIndices(StaticMeshGeometry basisWithUvs, StaticMeshSourceData asset,
        StaticMeshSourceLod lod, bool needTangents, MeshVertexColors colors)
    {
        if (lod.Description is null) throw new ConversionException($"LOD {lod.Index} has no topology for render indices");
        JObject settings = lod.Settings["BuildSettings"] as JObject ?? new JObject();
        JToken? vector = settings["BuildScale3D"];
        var scale = new Vector3((float)SourceAsset.Number(vector?["X"], 1), (float)SourceAsset.Number(vector?["Y"], 1),
            (float)SourceAsset.Number(vector?["Z"], 1));
        MeshScaledGeometry pending = MeshVertexScale.Prepare(basisWithUvs, scale, needTangents,
            asset.Settings.Value<bool>("legacy_tangent_scaling"));
        pending = pending with { Geometry = colors.Pack(pending.Geometry) };
        MeshTopology topology = MeshTopology.Read(lod.Description, basisWithUvs);
        MeshRenderGeometry render = MeshRenderIndexBuilder.Build(basisWithUvs, pending, topology,
            settings.Value<bool?>("bRemoveDegenerates") ?? true);
        return render with { Geometry = MeshStoragePrecision.Pack(render.Geometry, settings) };
    }
}
