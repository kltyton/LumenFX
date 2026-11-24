using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class RendererInfoSources
{
    internal static (SourceAsset Asset, JObject? Renderer) Resolve(AssetLibrary? library, SourceAsset asset,
            int index, string expectedClass)
    {
        if (index == 0) return (asset, null);
        if (index > 0) return (asset, Renderer(asset, asset.Object(index), expectedClass));
        string path = asset.Reference(index);
        if (library is null) throw new ConversionException($"Referenced renderer requires its source library: {path}");
        SourceAsset target = library.Load(path);
        SourceObject[] matches = target.Objects.Where(value =>
            string.Equals(QualifiedName(target, value), path, StringComparison.OrdinalIgnoreCase)).ToArray();
        if (matches.Length != 1) throw new ConversionException($"Expected one renderer object at {path}; found {matches.Length}");
        return (target, Renderer(target, matches[0], expectedClass));
    }

    private static JObject Renderer(SourceAsset asset, SourceObject value, string expectedClass)
    {
        if (value.ClassName != expectedClass)
            throw new ConversionException($"Renderer information requires {expectedClass}: {value.Name}");
        return RendererAttributeBindings.LoadedProperties(asset, value);
    }

    private static string QualifiedName(SourceAsset asset, SourceObject value)
    {
        List<string> names = new();
        HashSet<int> seen = new();
        while (true) {
            if (!seen.Add(value.Index)) throw new ConversionException($"Cyclic export ownership in {asset.Name}");
            names.Add(value.Name);
            if (value.Outer <= 0) {
                names.Reverse();
                string owner = value.Outer == 0 ? asset.Name : asset.Reference(value.Outer);
                return owner + "." + string.Join(".", names);
            }
            value = asset.Object(value.Outer);
        }
    }

    internal static int CustomVersion(SourceAsset asset, string key) =>
        (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
            .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), key, StringComparison.OrdinalIgnoreCase))
            ?.Value<int>("Version") ?? -1;
}
