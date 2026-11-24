using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureSource;

/** The deferred desktop placement and built low mips of a source RVT component. */
internal static class RuntimeVirtualTextureComponentSource
{
    internal static JObject Compile(AssetLibrary library, string path, string engineSource)
    {
        SourceAsset asset = library.Load(path);
        SourceObject component = asset.ResolveObject(path, value => value.ClassName == "RuntimeVirtualTextureComponent");
        string identity = asset.ObjectPath(component.Index);
        var target = Reference("VirtualTexture") ?? throw new ConversionException($"RVT component has no resolved VirtualTexture: {identity}");
        if (target.Object.ClassName != "RuntimeVirtualTexture")
            throw new ConversionException($"RVT component references a {target.Object.ClassName}: {identity}");
        var transform = ObjectPropertyFieldSource.CompileScene(asset, component, library)
            ?? throw new ConversionException($"RVT component transform could not be resolved: {identity}");
        string source = target.Asset.ObjectPath(target.Object.Index);
        var result = new JObject {
            ["source"] = source,
            ["transform"] = transform,
            ["hide_primitives"] = Property("bHidePrimitives")?.Value<bool>() ?? false,
            ["nanite_only"] = Property("bEnableForNaniteOnly")?.Value<bool>() ?? false,
            ["scalability_group"] = Property("bEnableScalability")?.Value<bool>() == true
                ? new JValue(Math.Clamp(Property("ScalabilityGroup")?.Value<int>() ?? 0, 0, 2)) : JValue.CreateNull(),
            ["minimum_material_quality"] = Property("bUseMinMaterialQuality")?.Value<bool>() == true
                ? new JValue(MaterialQuality(Property("MinInGameMaterialQuality"))) : JValue.CreateNull(),
            ["platform_enabled"] = Property("EnableInGamePerPlatform")?.DeepClone()
        };
        if (Reference("StreamingTexture") is { } builder) {
            if (builder.Object.ClassName != "VirtualTextureBuilder")
                throw new ConversionException($"RVT component references a {builder.Object.ClassName} streaming builder: {identity}");
            var data = builder.Object.Properties;
            int texture = data.Value<int?>("Texture") ?? 0;
            if (texture != 0 && data["BuildHash"] is { } hash &&
                (hash.Value<ulong>() & ~(1UL << 34)) == BuildHash(target.Object, source, Property("LossyCompressionAmount"), engineSource)) {
                var embedded = ObjectPropertyFieldSource.ResolveReference(builder.Asset, texture, library)
                    ?? throw new ConversionException($"RVT builder has an unresolved desktop texture: {identity}");
                if (embedded.Object.ClassName != "VirtualTexture2D")
                    throw new ConversionException($"RVT builder texture has type {embedded.Object.ClassName}: {identity}");
                result["streaming"] = new JObject {
                    ["source"] = embedded.Asset.ObjectPath(embedded.Object.Index),
                    ["only"] = Property("bUseStreamingMipsOnly")?.Value<bool>() ?? false
                };
            } else Console.Error.WriteLine($"RVT '{identity}' has stale or missing desktop streaming mips. Its pages will be rendered from the live scene.");
        }
        return result;

        JToken? Property(string name) => ObjectPropertyFieldSource.ComponentProperty(asset, component, library, name)?.Value is
            { Type: not JTokenType.Null } value ? value : null;
        (SourceAsset Asset, SourceObject Object)? Reference(string name)
        {
            if (ObjectPropertyFieldSource.ComponentProperty(asset, component, library, name) is not { } field) return null;
            int index = field.Value.Type == JTokenType.Null ? 0 : field.Value.Value<int>();
            if (index == 0) return null;
            return ObjectPropertyFieldSource.ResolveReference(field.Asset, index, library)
                ?? throw new ConversionException($"Unresolved RVT component property {name}: {identity}");
        }
    }

    private static string MaterialQuality(JToken? value)
        => (value?.Value<string>() ?? "Low").Split("::").Last() switch {
            "0" or "Low" => "Low", "1" or "Medium" => "Medium", "2" or "High" => "High", "3" or "Epic" => "Epic",
            var invalid => throw new ConversionException($"Invalid RVT minimum material quality {invalid}")
        };

    private static ulong BuildHash(SourceObject texture, string path, JToken? compression, string engineSource)
    {
        var definition = RuntimeVirtualTextureSource.Compile(texture, path);
        const string textureHeader = "Engine/Source/Runtime/Engine/Classes/Engine/TextureDefines.h";
        ulong material = EnumOrdinal(engineSource, "Engine/Source/Runtime/Engine/Public/VT/RuntimeVirtualTextureEnum.h",
            "enum class ERuntimeVirtualTextureMaterialType", definition["material"]!);
        ulong group = EnumOrdinal(engineSource, textureHeader, "enum TextureGroup", definition["lod_group"]!);
        ulong lossy = EnumOrdinal(engineSource, textureHeader, "enum ETextureLossyCompressionAmount", compression ?? new JValue("TLCA_Default"));
        ulong size = 1UL << Math.Clamp(definition.Value<int>("tile_size") + 6, 6, 10);
        ulong border = (ulong)Math.Clamp(definition.Value<int>("tile_border"), 0, 4) * 2;
        return 2UL | material << 4 | size << 8 | border << 20 | group << 24
            | (definition.Value<bool>("compress") ? 1UL << 32 : 0)
            | (definition.Value<bool>("packed_table") ? 1UL << 33 : 0)
            | (definition.Value<bool>("low_quality_compression") ? 1UL << 35 : 0) | lossy << 36;
    }

    private static ulong EnumOrdinal(string root, string header, string declaration, JToken property)
    {
        if (property.Type == JTokenType.Integer) return property.Value<ulong>();
        string name = (property.Value<string>() ?? "").Split("::").Last();
        if (ulong.TryParse(name, out ulong ordinal)) return ordinal;
        string source = File.ReadAllText(Path.Combine(root, header));
        int start = source.IndexOf(declaration, StringComparison.Ordinal);
        int body = start < 0 ? -1 : source.IndexOf('{', start);
        int end = body < 0 ? -1 : source.IndexOf("};", body, StringComparison.Ordinal);
        if (end < 0) throw new ConversionException($"Missing fixed UE source enum {declaration}");
        var entries = Regex.Matches(source[(body + 1)..end], @"(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:UMETA\(|,)");
        for (int i = 0; i < entries.Count; i++)
            if (entries[i].Groups[1].Value == name) return (ulong)i;
        throw new ConversionException($"Unknown {declaration} value {name}");
    }
}
