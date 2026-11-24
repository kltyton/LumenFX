using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureSource;

/** Reads source primitive eligibility and ordering independently of the host vertex factory. */
internal static class RuntimeVirtualTexturePrimitiveSource
{
    internal static JObject Compile(AssetLibrary library, string path)
    {
        SourceAsset asset = library.Load(path);
        SourceObject primitive = asset.ResolveObject(path, value => ObjectPropertyFieldSource.IsOfClass(asset,
            value.Raw.Value<int>("ClassIndex"), "/Script/Engine.PrimitiveComponent", library));
        var targets = new SortedSet<string>(SourceNameOrder.Instance);
        if (ObjectPropertyFieldSource.ComponentProperty(asset, primitive, library, "RuntimeVirtualTextures") is { } binding &&
            binding.Value is JArray values) {
            foreach (int index in values.Values<int>()) {
                if (index == 0) continue;
                var target = ObjectPropertyFieldSource.ResolveReference(binding.Asset, index, library)
                    ?? throw new ConversionException($"Unresolved runtime virtual texture on primitive {path}");
                if (target.Object.ClassName != "RuntimeVirtualTexture")
                    throw new ConversionException($"Invalid runtime virtual texture on primitive {path}: {target.Object.ClassName}");
                targets.Add(target.Asset.ObjectPath(target.Object.Index));
            }
        }
        string pass = (Property("VirtualTextureRenderPassType")?.Value<string>() ?? "Exclusive").Split("::").Last() switch {
            "0" or "Never" => "Never", "1" or "Exclusive" => "Exclusive", "2" or "Always" => "Always",
            var invalid => throw new ConversionException($"Invalid RVT main pass type {invalid}: {path}")
        };
        return new JObject {
            ["targets"] = new JArray(targets), ["main_pass"] = pass,
            ["lod_bias"] = Math.Clamp(Property("VirtualTextureLodBias")?.Value<int>() ?? 0, -7, 8),
            ["skip_mips"] = Math.Clamp(Property("VirtualTextureCullMips")?.Value<int>() ?? 0, 0, 7),
            ["minimum_coverage"] = Math.Clamp(Property("VirtualTextureMinCoverage")?.Value<int>() ?? 0, 0, 7),
            ["priority"] = Math.Clamp(Property("TranslucencySortPriority")?.Value<int>() ?? 0, short.MinValue, short.MaxValue)
        };

        JToken? Property(string name) => ObjectPropertyFieldSource.ComponentProperty(asset, primitive, library, name)?.Value is
            { Type: not JTokenType.Null } value ? value : null;
    }
}
