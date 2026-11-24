using LumenFx.Compiler.Unreal.TextureSource;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal sealed record StaticMeshMaterialSlot(string Name, string ImportedName, string Material);
internal sealed record StaticMeshSourceLod(int Index, JObject Settings, MeshDescriptionData? Description,
    StaticMeshGeometry? Geometry);
internal sealed record StaticMeshSourceData(string Source, JObject Settings,
    IReadOnlyList<StaticMeshMaterialSlot> Materials, IReadOnlyList<StaticMeshSourceLod> Lods)
{
    internal SourceAsset? ArchiveOwner { get; init; }
    internal IReadOnlyList<LumenFx.Compiler.Unreal.Geometry.MeshBuild.PreparedMeshLod>? PreparedLods { get; init; }
    internal StaticMeshSourceLod? HiResLod { get; init; }
    internal LumenFx.Compiler.Unreal.Geometry.MeshBuild.NativeMeshRenderResult? Rendered { get; init; }
    internal JObject? NativeSettings { get; init; }
    internal IReadOnlyList<string> NativeObjectPaths { get; init; } = Array.Empty<string>();
    internal JArray Sockets { get; init; } = new();
}

internal static class StaticMeshSourceCompiler
{
    internal static StaticMeshSourceData Compile(SourceAsset asset, string? oodleLibrary)
        => Compile(asset, asset.ResolveObject(asset.Name, value => value.ClassName == "StaticMesh"), oodleLibrary);

    internal static StaticMeshSourceData Compile(SourceAsset asset, string path, string? oodleLibrary)
        => Compile(asset, asset.ResolveObject(path, value => value.ClassName == "StaticMesh"), oodleLibrary);

    private static StaticMeshSourceData Compile(SourceAsset asset, SourceObject mesh, string? oodleLibrary)
    {
        JObject properties = mesh.Properties;
        JArray models = properties["SourceModels"] as JArray
            ?? throw new ConversionException($"Static mesh has no source LOD models: {asset.Name}");
        JArray slots = properties["StaticMaterials"] as JArray
            ?? throw new ConversionException($"Static mesh has no material slots: {asset.Name}");
        var materials = slots.Cast<JObject>().Select(slot => new StaticMeshMaterialSlot(
            slot.Value<string>("MaterialSlotName") ?? "None", slot.Value<string>("ImportedMaterialSlotName") ?? "None",
            asset.ObjectPath(slot.Value<int>("MaterialInterface")))).ToArray();
        using var oodle = oodleLibrary is null ? null : new OodleDataCodec(oodleLibrary);
        var lods = new List<StaticMeshSourceLod>();
        StaticMeshSourceLod ReadLod(JObject model, int index)
        {
            int reference = model.Value<int>("StaticMeshDescriptionBulkData");
            MeshDescriptionData? description = null;
            if (reference != 0)
            {
                SourceObject bulk = asset.Object(reference);
                if (bulk.Outer != mesh.Index)
                    throw new ConversionException($"Static mesh source LOD references a different owner: {asset.Name}.{bulk.Name}");
                description = MeshDescriptionSource.Read(asset, bulk, oodle);
            }
            return new StaticMeshSourceLod(index, Select(model, "BuildSettings", "ReductionSettings", "ScreenSize",
                    "bImportWithBaseMesh"), description,
                description is null ? null : StaticMeshGeometryCompiler.Compile(description));
        }
        foreach (JObject model in models.Cast<JObject>()) lods.Add(ReadLod(model, lods.Count));
        if (lods.Count == 0 || lods[0].Geometry is null)
            throw new ConversionException($"Static mesh has no LOD0 source geometry: {asset.Name}");
        JObject settings = Select(properties, "bAutoComputeLODScreenSize", "MinLOD", "MinQualityLevelLOD", "bOverrideMinLOD", "LODGroup", "ExtendedBounds",
            "PositiveBoundsExtension", "NegativeBoundsExtension", "SectionInfoMap", "OriginalSectionInfoMap", "NaniteSettings", "LightmapUVVersion",
            "bSupportRayTracing", "bAllowCPUAccess", "bSupportUniformlyDistributedSampling", "bSupportGpuUniformlyDistributedSampling",
            "bGenerateMeshDistanceField", "LODStreaming", "NumStreamedLODs", "NeverStream");
        if (models[0]?["BuildSettings"]?["DistanceFieldReplacementMesh"] is { Type: JTokenType.Integer } replacement
                && replacement.Value<int>() != 0)
            settings["LumenFxDistanceFieldReplacementMesh"] = asset.ObjectPath(replacement.Value<int>());
        settings["legacy_tangent_scaling"] = LegacyTangentScaling(asset, properties, models);
        bool nanite = properties["NaniteSettings"]?.Value<bool?>("bEnabled") == true;
        IReadOnlyList<string> nativePaths = Array.Empty<string>();
        JObject? nativeSettings = nanite ? asset.NativeProperties(mesh, out nativePaths, "NaniteSettings") : null;
        return new StaticMeshSourceData(asset.ObjectPath(mesh.Index), settings, materials, lods) {
            ArchiveOwner = asset,
            HiResLod = nanite && properties["HiResSourceModel"] is JObject hiRes ? ReadLod(hiRes, -1) : null,
            NativeSettings = nativeSettings, NativeObjectPaths = nativePaths, Sockets = Sockets(asset, properties)
        };
    }

    private static JArray Sockets(SourceAsset asset, JObject properties)
    {
        var result = new JArray();
        foreach (int reference in (properties["Sockets"] as JArray ?? new()).Values<int>()) {
            if (reference == 0) continue;
            if (asset.ReferencedClass(reference) != "StaticMeshSocket")
                throw new ConversionException($"Invalid static mesh socket object: {asset.ObjectPath(reference)}");
            string path = asset.ObjectPath(reference);
            var owner = reference > 0 ? asset : asset.Library?.Load(path)
                ?? throw new ConversionException($"Missing static mesh socket package: {path}");
            var socket = reference > 0 ? owner.Object(reference) : owner.ResolveObject(path, value => value.ClassName == "StaticMeshSocket");
            var data = socket.Properties;
            var rotation = data["RelativeRotation"];
            result.Add(new JObject {
                ["name"] = data.Value<string>("SocketName") ?? "None",
                ["location"] = Vector(data["RelativeLocation"], 0),
                ["rotation"] = new JArray(SourceAsset.Number(rotation?["Pitch"], 0), SourceAsset.Number(rotation?["Yaw"], 0),
                    SourceAsset.Number(rotation?["Roll"], 0)),
                ["scale"] = Vector(data["RelativeScale"], 1)
            });
        }
        return result;
    }

    private static JArray Vector(JToken? value, double fallback) => new(SourceAsset.Number(value?["X"], fallback),
        SourceAsset.Number(value?["Y"], fallback), SourceAsset.Number(value?["Z"], fallback));

    private static bool LegacyTangentScaling(SourceAsset asset, JObject properties, JArray models)
    {
        const string fortniteVersion = "{601D1886-AC64-4F84-AA16-D3DE0DEAC7D6}";
        int version = (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
            .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), fortniteVersion, StringComparison.OrdinalIgnoreCase))
            ?.Value<int>("Version") ?? -1;
        if (version >= 127) return properties.Value<bool?>("bUseLegacyTangentScaling") ?? false;
        return models.Cast<JObject>().Any(model => {
            JToken? scale = model["BuildSettings"]?["BuildScale3D"];
            double x = SourceAsset.Number(scale?["X"], 1), y = SourceAsset.Number(scale?["Y"], 1), z = SourceAsset.Number(scale?["Z"], 1);
            double tolerance = 1.0e-4f;
            return !(Math.Abs(x - y) <= tolerance && Math.Abs(x - z) <= tolerance && Math.Abs(y - z) <= tolerance);
        });
    }

    private static JObject Select(JObject source, params string[] names) => new(names
        .Where(name => source[name] is not null).Select(name => new JProperty(name, source[name]!.DeepClone())));
}
