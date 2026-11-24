using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class StaticMeshDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceStaticMesh";

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "IsValid" or "GetVertexCount" or "GetTriangleCount" or "GetFilteredTriangleCount" or "GetUnfilteredTriangleCount"
                or "GetSectionCount" or "GetFilteredSectionCount" or "GetUnfilteredSectionCount"
                or "GetSocketCount" or "GetFilteredSocketCount" or "GetUnfilteredSocketCount" or "GetInstanceIndex" => (0, 1),
            "IsValidVertex" or "IsValidTriangle" or "IsValidFilteredTriangle" or "IsValidUnfilteredTriangle" or "IsValidSection"
                or "GetFilteredTriangleAt" or "GetUnfilteredTriangleAt" or "GetFilteredSectionAt" or "GetUnfilteredSectionAt"
                or "GetSectionTriangleCount" or "GetFilteredSocket" or "GetUnfilteredSocket" => (1, 1),
            "GetVertexColor" => (1, 4),
            "GetVertexUV" => (2, 2),
            "GetVertex" or "GetVertexWS" => (1, 15),
            "GetVertexWSInterpolated" => (2, 15),
            "GetTriangleIndices" => (1, 3),
            "RandomVertex" or "RandomSocket" or "RandomFilteredSocket" or "RandomUnfilteredSocket"
                or "RandomSection" or "RandomFilteredSection" or "RandomUnfilteredSection" => (3, 1),
            "RandomTriangle" or "RandomFilteredTriangle" or "RandomUnfilteredTriangle" => (3, 4),
            "RandomSectionTriangle" => (4, 4),
            "GetTriangle" or "GetTriangleWS" => (4, 15),
            "GetTriangleWSInterpolated" => (5, 15),
            "GetTriangleColor" => (4, 4),
            "GetTriangleUV" => (5, 2),
            "GetSocketTransform" or "GetSocketTransformWS" or "GetFilteredSocketTransform" or "GetFilteredSocketTransformWS"
                or "GetUnfilteredSocketTransform" or "GetUnfilteredSocketTransformWS" => (1, 13),
            "GetSocketTransformWSInterpolated" or "GetFilteredSocketTransformWSInterpolated"
                or "GetUnfilteredSocketTransformWSInterpolated" => (2, 13),
            "GetSectionTriangleAt" => (2, 1),
            "SetInstanceIndex" => (1, 0),
            "BuildUvMapping" => (1, 0),
            "GetTriangleCoordAtUV" => (4, 5),
            "GetTriangleCoordInAabb" => (5, 5),
            "GetWorldVelocity" => (0, 3),
            "GetLocalToWorld" or "GetLocalToWorldInverseTransposed" => (0, 16),
            "GetPreSkinnedLocalBounds" => (0, 15),
            "GetMeshBounds" or "GetMeshBoundsWS" => (0, 18),
            "DeprecatedRandomSection" => (0, 1),
            "DeprecatedRandomTriCoord" => (0, 4),
            "DeprecatedRandomTriCoordOnSection" => (1, 4),
            "DeprecatedGetVertexPosition" or "DeprecatedGetVertexPositionWS" => (1, 3),
            "DeprecatedGetTriPosition" or "DeprecatedGetTriPositionWS" or "DeprecatedGetTriNormal" or "DeprecatedGetTriNormalWS" => (4, 3),
            "DeprecatedGetTriTangents" or "DeprecatedGetTriTangentsWS" => (4, 9),
            "DeprecatedGetTriPositionAndVelocityWS" => (4, 6),
            "DeprecatedGetTriColor" => (4, 4),
            "DeprecatedGetTriUV" => (5, 2),
            _ => (-1, -1)
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Niagara static mesh CPU signature is not implemented: {name} ({inputs}, {outputs})");
    }

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        string[] modes = ["Default", "Source", "AttachParent", "DefaultMeshOnly", "MeshParameterBinding"];
        string mode = properties["SourceMode"] switch {
            null => modes[0],
            { Type: JTokenType.Integer } value when value.Value<int>() >= 0 && value.Value<int>() < modes.Length
                => modes[value.Value<int>()],
            { Type: JTokenType.String } value => value.Value<string>()!.Split("::").Last(),
            _ => throw new ConversionException("Invalid Niagara static mesh source mode")
        };
        if (!modes.Contains(mode, StringComparer.Ordinal))
            throw new ConversionException($"Unknown Niagara static mesh source mode: {mode}");

        int mesh = properties.Value<int?>("DefaultMesh") ?? 0;
        if (mesh != 0 && asset.ReferencedClass(mesh) != "StaticMesh")
            throw new ConversionException($"Niagara static mesh default references a different class: {asset.ObjectPath(mesh)}");
        var result = new JObject {
            ["source_mode"] = mode,
            ["source_actor"] = DataInterfaceInstanceSources.MeshSourceActor(asset, properties),
            ["mesh_parameter"] = Binding(properties, "MeshParameterBinding"),
            ["lod_parameter"] = Binding(properties, "LODIndexUserParameter"),
            ["lod"] = properties.Value<int?>("LODIndex") ?? 0,
            ["instance"] = properties.Value<int?>("InstanceIndex") ?? -1,
            ["capture_transforms"] = properties.Value<bool?>("bCaptureTransformsPerFrame") ?? true,
            ["physics_velocity"] = properties.Value<bool?>("bUsePhysicsBodyVelocity") ?? false,
            ["sample_streaming_lods"] = properties.Value<bool?>("bAllowSamplingFromStreamingLODs") ?? false,
            ["allowed_slots"] = new JArray((properties["SectionFilter"]?["AllowedMaterialSlots"] as JArray ?? new()).Values<int>()),
            ["filtered_sockets"] = new JArray((properties["FilteredSockets"] as JArray ?? new()).Values<string>())
        };
        if (properties["LODIndexUserParameter"]?["Parameter"] is JObject parameter
                && result.Value<string>("lod_parameter") is string lodName && lodName is not ("" or "None")) {
            var value = ExecutionNumericParameterSource.Read(asset, parameter);
            result["lod_key"] = new JObject { ["name"] = value.Name, ["source_type"] = value.Type.Path,
                ["underlying"] = value.Type.UnderlyingType, ["flags"] = value.Type.Flags };
        }
        if (mesh != 0) result["asset_source"] = asset.ObjectPath(mesh);
        return result;
    }

    private static string Binding(JObject source, string name) => source[name]?["Parameter"]?.Value<string>("name") ?? "";

}
