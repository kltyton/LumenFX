using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SkeletalMeshDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceSkeletalMesh";

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var signature = name switch {
            "GetPreSkinnedLocalBounds" => (0, 15),
            "GetBoneCount" or "GetFilteredBoneCount" or "GetUnfilteredBoneCount" or "GetFilteredSocketCount"
                or "GetFilteredSocketOrBoneCount" or "GetVertexCount" or "GetFilteredVertexCount"
                or "GetTriangleCount" or "GetFilteredTriangleCount" => (0, 1),
            "IsValidBone" or "GetParentBone" or "GetFilteredBone" or "GetUnfilteredBone" or "GetFilteredSocket"
                or "GetFilteredSocketOrBone" or "IsValidVertex" or "IsValidFilteredVertex" or "GetFilteredVertex" => (1, 1),
            "RandomBone" or "RandomFilteredBone" or "RandomUnfilteredBone" or "RandomFilteredSocket"
                or "RandomFilteredSocketOrBone" or "RandomVertex" or "RandomFilteredVertex" => (3, 1),
            "GetFilteredSocketTransform" => (2, 10),
            "GetSkinnedBoneData" or "GetSkinnedBoneDataWS" => (1, 13),
            "GetSkinnedBoneDataInterpolated" or "GetSkinnedBoneDataWSInterpolated" => (2, 13),
            "GetVertexColor" => (1, 4),
            "GetVertexUV" => (2, 2),
            "GetVertexData" => (1, 12),
            "GetSkinnedVertexData" or "GetSkinnedVertexDataWS" => (1, 15),
            "GetSkinnedVertexDataInterpolated" or "GetSkinnedVertexDataInterpolatedWS" => (2, 15),
            "RandomTriCoord" or "RandomTriangle" or "RandomFilteredTriangle" => (3, 4),
            "IsValidTriCoord" => (4, 1),
            "GetTriangleIndices" or "GetTriCoordVertices" => (1, 3),
            "GetTriangleData" => (4, 12),
            "GetSkinnedTriangleData" or "GetSkinnedTriangleDataWS" => (4, 15),
            "GetSkinnedTriangleDataInterpolated" or "GetSkinnedTriangleDataWSInterpolated" => (5, 15),
            "GetSkinnedTriangleVertexData" or "GetSkinnedTriangleVertexDataWS" => (1, 45),
            "GetSkinnedTriangleVertexDataInterpolated" or "GetSkinnedTriangleVertexDataWSInterpolated" => (2, 45),
            "GetTriColor" or "GetFilteredTriangle" => (4, 4),
            "GetTriUV" => (5, 2),
            "GetTriangleCoordAtUV" => (4, 5),
            "GetTriangleCoordInAabb" => (5, 5),
            _ => (-1, -1)
        };
        if (signature != (inputs, outputs))
            throw new ConversionException($"Niagara skeletal mesh CPU signature is not implemented: {name} ({inputs}, {outputs})");
    }

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        var mode = EnumValue(properties["SourceMode"], 0, ["Default", "Source", "AttachParent", "DefaultMeshOnly", "ChildrenOnly"]);
        var skinning = EnumValue(properties["SkinningMode"], 1, ["None", "SkinOnTheFly", "PreSkin"]);
        var data = new JObject {
            ["source_mode"] = mode,
            ["source_actor"] = DataInterfaceInstanceSources.MeshSourceActor(asset, properties),
            ["source_component"] = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["SourceComponent"]),
            ["component_class"] = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["OptionalComponentClass"]),
            ["component_tags"] = Names(properties, "ComponentTags"),
            ["mesh_parameter"] = properties["MeshUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["skinning"] = skinning,
            ["regions"] = Names(properties, "SamplingRegions"),
            ["whole_mesh_lod"] = properties.Value<int?>("WholeMeshLOD") ?? -1,
            ["filtered_bones"] = Names(properties, "FilteredBones"),
            ["filtered_sockets"] = Names(properties, "FilteredSockets"),
            ["exclude_bone"] = properties.Value<bool?>("bExcludeBone") ?? false,
            ["excluded_bone"] = properties.Value<string>("ExcludeBoneName") ?? "root",
            ["uv_set"] = properties.Value<int?>("UvSetIndex") ?? 0,
            ["require_current"] = properties.Value<bool?>("bRequireCurrentFrameData") ?? true,
            ["read_deformed"] = properties.Value<bool?>("bReadDeformedGeometry") ?? true
        };
        int mesh = properties.Value<int?>("DefaultMesh") ?? 0;
        if (mesh != 0) {
            if (asset.ReferencedClass(mesh) != "SkeletalMesh")
                throw new ConversionException($"Niagara skeletal default references a different mesh class: {asset.ObjectPath(mesh)}");
            data["asset_source"] = asset.ObjectPath(mesh);
        }
        return data;
    }

    private static JArray Names(JObject source, string name) => new((source[name] as JArray ?? new()).Values<string>());

    private static string EnumValue(JToken? token, int fallback, string[] names)
    {
        string? value = token switch {
            null => names[fallback],
            { Type: JTokenType.Integer } ordinal when ordinal.Value<int>() >= 0 && ordinal.Value<int>() < names.Length => names[ordinal.Value<int>()],
            { Type: JTokenType.String } text => text.Value<string>()?.Split("::").Last(),
            _ => null
        };
        return value is not null && names.Contains(value, StringComparer.Ordinal) ? value
            : throw new ConversionException($"Unknown Niagara skeletal mesh mode: {token}");
    }
}
