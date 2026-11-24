using LumenFx.Compiler.Unreal.Geometry;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** ArrayMesh supplies renderer entries through its DI slot and exposes no simulation functions. */
internal static class MeshArrayInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceArrayMesh";
    internal const string SourceEntries = "mesh_array_source_entries";

    internal static JArray Describe(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var entries = new JArray();
        foreach (JObject item in source.Object?.Properties["MeshData"] as JArray ?? new JArray())
            entries.Add(MeshRendererCompiler.SourceEntry(source.Asset, item));
        return entries;
    }

    internal static void BindRenderer(SourceAsset owner, JObject source, string emitterNamespace, JObject mesh,
        IReadOnlyDictionary<string, JArray> arrays, Func<string, StaticMeshSourceData> sourceMesh, Func<string, string> material)
    {
        if (source["MeshesBinding"]?["ResolvedParameter"] is not JObject parameter
            || RendererFieldBindings.Variable.From(owner, parameter, emitterNamespace) is not { } variable
            || !SourceNameOrder.Equal(variable.StoreTypeName, ClassName)) return;
        var values = new JObject();
        foreach (var (identity, entries) in arrays) {
            var bound = (JArray)entries.DeepClone();
            foreach (JObject entry in bound) MeshRendererCompiler.BindEntryMaterials(entry, sourceMesh, material);
            values[identity] = bound;
        }
        mesh["mesh_array"] = new JObject {
            ["slot"] = new JObject { ["name"] = variable.Name, ["source_type"] = variable.Type,
                ["underlying"] = variable.UnderlyingType, ["flags"] = variable.Flags, ["storage"] = "interface" },
            ["values"] = values };
    }

    internal static IReadOnlyDictionary<string, JArray> TakeSourceEntries(JObject execution)
    {
        var arrays = new Dictionary<string, JArray>(SourceNameOrder.Instance);
        foreach (var item in execution.Descendants().OfType<JObject>()
            .Where(item => item[SourceEntries] is JArray).ToArray()) {
            var identity = item.Value<string>("id") ?? throw new ConversionException("Mesh array has no object identity");
            var entries = (JArray)item[SourceEntries]!;
            if (!arrays.TryAdd(identity, entries) && !JToken.DeepEquals(arrays[identity], entries))
                throw new ConversionException($"Mesh array source descriptors disagree: {identity}");
            item.Remove(SourceEntries);
        }
        return arrays;
    }
}
