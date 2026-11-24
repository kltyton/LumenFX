using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class DynamicMeshInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceDynamicMesh";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var asset = source?.Asset ?? owner;
        var properties = source?.Object?.Properties;
        return new JObject {
            ["vertices"] = properties?.Value<int?>("NumVertices") ?? 0,
            ["texcoords"] = properties?.Value<int?>("NumTexCoords") ?? 0,
            ["has_colors"] = properties?.Value<bool?>("bHasColors") ?? false,
            ["has_tangent_basis"] = properties?.Value<bool?>("bHasTangentBasis") ?? false,
            ["clear_triangles_per_frame"] = properties?.Value<bool?>("bClearTrianglesPerFrame") ?? false,
            ["bounds"] = VectorProgramCompiler.Bounds(properties?["DefaultBounds"]),
            ["sections"] = new JArray((properties?["Sections"] as JArray ?? new()).OfType<JObject>().Select(section => new JObject {
                ["triangles"] = section.Value<int?>("NumTriangles") ?? 0,
                ["material_index"] = section.Value<int?>("MaterialIndex") ?? 0
            })),
            ["materials"] = new JArray((properties?["Materials"] as JArray ?? new()).OfType<JObject>().Select(material => new JObject {
                ["material_source"] = ObjectPropertyReaderCompiler.ObjectPath(asset, material["Material"]),
                ["user_parameter"] = material["MaterialUserParamBinding"]?["Parameter"]?.Value<string>("name")
                    ?? material["MaterialUserParamBinding"]?["Parameter"]?.Value<string>("Name") ?? ""
            }))
        };
    }

    internal static IEnumerable<(string Source, string Parameter)> Materials(JToken execution)
    {
        if (execution is JObject value) {
            if (value["dynamic_mesh"]?["materials"] is JArray materials) {
                yield return ("", "");
                foreach (var material in materials.OfType<JObject>())
                    yield return (material.Value<string>("material_source") ?? "", material.Value<string>("user_parameter") ?? "");
            }
            foreach (var property in value.Properties())
                foreach (var material in Materials(property.Value)) yield return material;
        } else if (execution is JArray items) {
            foreach (var item in items)
                foreach (var material in Materials(item)) yield return material;
        }
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetMeshProperties" => (0, 5), "GetSectionCount" => (0, 1), "GetSectionData" => (1, 3),
            "GetLocalBounds" => (0, 6), "SetMeshProperties" => (5, 0), "SetSectionCount" => (2, 0),
            "SetSectionData" => (4, 0), "SetLocalBounds" => (7, 0), "ClearAllSectionTriangles" => (1, 0),
            "ClearSectionTriangles" => (2, 0), "AllocateSectionTriangles" => (3, 2),
            "GetTriangleVertices" or "GetVertexPosition" => (1, 4), "GetVertexTangentBasis" => (1, 10),
            "GetVertexTexCoord" => (2, 3), "GetVertexColor" => (1, 5), "GetVertexData" => (1, 19),
            "SetTriangleVertices" or "SetVertexPosition" or "SetVertexTexCoord" => (5, 0),
            "SetVertexTangentBasis" => (11, 0), "SetVertexColor" => (6, 0), "SetVertexData" => (20, 0),
            "AppendTriangle" => (56, 1),
            _ => throw new ConversionException($"Unknown Niagara Dynamic Mesh function {name}")
        };
        if (shape != (inputs, outputs)) throw new ConversionException($"Invalid Niagara Dynamic Mesh signature: {name} ({inputs}, {outputs})");
    }
}
