using LumenFx.Compiler.Unreal.Geometry;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class MeshRendererInfoCompiler
{
    internal static JObject CompileInterface(AssetLibrary? library, SourceAsset asset, SourceObject? data, string? reference = null)
    {
        SourceAsset owner = asset;
        if (data is null && reference is not null && !reference.EndsWith(".Default__NiagaraDataInterfaceMeshRendererInfo", StringComparison.Ordinal)) {
            if (library is null) throw new ConversionException($"Referenced mesh information interface requires its source library: {reference}");
            owner = library.Load(reference);
            data = owner.ResolveObject(reference, value => value.ClassName == "NiagaraDataInterfaceMeshRendererInfo");
        }
        return Compile(library, owner, data?.Properties.Value<int?>("MeshRenderer") ?? 0);
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "GetNumMeshes" => inputs == 0 && outputs == 1,
            "GetMeshLocalBounds" => inputs == 1 && outputs == 9,
            "GetSubUVDetails" => inputs == 0 && outputs == 3,
            _ => false
        };
        if (!valid) throw new ConversionException($"Invalid mesh renderer information function {name}: {inputs} inputs, {outputs} outputs");
    }

    internal static JObject Compile(AssetLibrary? library, SourceAsset asset, int rendererIndex)
    {
        var resolved = RendererInfoSources.Resolve(library, asset, rendererIndex, "NiagaraMeshRendererProperties");
        JObject? renderer = resolved.Renderer;
        JArray meshes = renderer is null ? new JArray() : MeshRendererCompiler.SourceEntries(renderer);
        JArray sources = new();
        foreach (JObject mesh in meshes) {
            int reference = mesh.Value<int?>("Mesh") ?? 0;
            sources.Add(new JObject {
                ["source"] = reference == 0 ? null : resolved.Asset.ObjectPath(reference),
                ["scale"] = new JArray(SourceAsset.Number(mesh["Scale"]?["X"], 1),
                    SourceAsset.Number(mesh["Scale"]?["Y"], 1), SourceAsset.Number(mesh["Scale"]?["Z"], 1))
            });
        }
        int version = RendererInfoSources.CustomVersion(resolved.Asset, "{FCF57AFA-5076-4283-B9A9-E658FFA02D32}");
        JObject info = new() {
            ["mesh_count"] = meshes.Count,
            ["sub_image_blend"] = NiagaraSpriteRendererSource.SubImageBlend(version, renderer),
            ["sub_image_size"] = new JArray(SourceAsset.Number(renderer?["SubImageSize"]?["X"], 1),
                    SourceAsset.Number(renderer?["SubImageSize"]?["Y"], 1))
        };
        if (sources.OfType<JObject>().Any(entry => entry.Value<string>("source") is not null)) info["mesh_sources"] = sources;
        else info["bounds"] = new JArray(Enumerable.Repeat(0.0f, checked(meshes.Count * 6)));
        return info;
    }

    internal static IEnumerable<string> PendingSources(JObject definition) => Information(definition)
            .SelectMany(info => info["mesh_sources"] as JArray ?? new JArray()).OfType<JObject>()
            .Select(entry => entry.Value<string>("source")).OfType<string>().Distinct(StringComparer.Ordinal);

    internal static void Complete(JObject definition, IReadOnlyDictionary<string, MeshSourceBounds> meshes)
    {
        foreach (JObject info in Information(definition).Where(info => info["mesh_sources"] is JArray).ToArray()) {
            JArray bounds = new();
            foreach (JObject entry in (JArray)info["mesh_sources"]!) {
                if (entry.Value<string>("source") is not { } source) {
                    foreach (float value in Enumerable.Repeat(0.0f, 6)) bounds.Add(value);
                    continue;
                }
                if (!meshes.TryGetValue(source, out var box)) throw new ConversionException($"Missing built mesh bounds for renderer information: {source}");
                JArray scale = (JArray)entry["scale"]!;
                double[] origin = { box.Origin.X, box.Origin.Y, box.Origin.Z }, extent = { box.Extent.X, box.Extent.Y, box.Extent.Z };
                for (int axis = 0; axis < 3; axis++) bounds.Add((float)((origin[axis] - extent[axis]) * scale[axis].Value<double>()));
                for (int axis = 0; axis < 3; axis++) bounds.Add((float)((origin[axis] + extent[axis]) * scale[axis].Value<double>()));
            }
            info["bounds"] = bounds;
            info.Remove("mesh_sources");
        }
    }

    private static IEnumerable<JObject> Information(JObject definition)
    {
        if (definition["niagara"] is not JObject niagara) yield break;
        foreach (JObject binding in niagara.DescendantsAndSelf().OfType<JObject>()) {
            string? field = Tag(binding, "interface_type") == "mesh_renderer_info" ? "interface_data"
                    : Tag(binding, "class") == "NiagaraDataInterfaceMeshRendererInfo" ? "mesh_info" : null;
            if (field is not null) yield return binding[field] as JObject
                    ?? throw new ConversionException($"Mesh renderer information has no {field} payload");
        }
    }

    private static string? Tag(JObject value, string name) => value[name] is JValue { Type: JTokenType.String } tag
            ? tag.Value as string : null;
}
