using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

internal sealed record CompiledFontSampler(string Name, string Association, int Index, string Sampler, string? ParentTexture);

public sealed partial class MaterialCompiler
{
    private readonly Dictionary<string, CompiledFontSampler> fontSamplers = new(StringComparer.Ordinal);
    private readonly Dictionary<string, IReadOnlyDictionary<int, string>> fontAssets = new(SourceNameOrder.Instance);

    private Expr FontSample(Context context, SourceObject node, int output)
    {
        JObject properties = node.Properties;
        int reference = properties.Value<int?>("Font") ?? 0;
        if (reference == 0) throw new ConversionException($"Missing font in {context.Asset.Name}:{node.Name}");
        string font = context.Asset.ObjectPath(reference);
        SourceAsset fontAsset = reference > 0 ? context.Asset : library.Load(font);
        int page = properties.Value<int?>("FontTexturePage") ?? 0;
        string baseTexture = FontPage(fontAsset, font, page, true)
            ?? throw new ConversionException($"Invalid font page {page}: {font}");
        CaptureFontPages(fontAsset, font);
        TextureSettings baseSettings = FontTextureSettings(baseTexture);
        var samplerType = baseSettings.Compression == "TC_DistanceFieldFont" ? MaterialSamplerType.DistanceFieldFont
                : baseSettings.Srgb ? MaterialSamplerType.Color : MaterialSamplerType.LinearColor;
        string? name = node.ClassName == "MaterialExpressionFontSampleParameter" ? properties.Value<string>("ParameterName") : null;
        if (name == "None" || string.IsNullOrEmpty(name)) name = null;
        string texture = baseTexture;
        if (name is not null)
            texture = FontParameterPage(ParameterValue(context, InstanceParameterType.Font, name)) ?? baseTexture;
        FontTextureSettings(texture);
        Expr sampler = RegisterTextureSampler(context, texture, name, samplerType);
        if (name is not null) {
            string? parent = null;
            if (sourceDynamicMaterial) {
                var key = new MaterialParameterKey(name, context.ParameterAssociation, context.ParameterIndex);
                var inherited = context.LayerParameters?.ParentFonts ?? parentFontParameters;
                parent = FontParameterPage(inherited.GetValueOrDefault(key)
                    ?? FunctionParameterValue(context, InstanceParameterType.Font, name)) ?? baseTexture;
                if (parent == texture) parent = null;
                else FontTextureSettings(parent);
            }
            fontSamplers[sampler.Code] = new(name, context.ParameterAssociation, context.ParameterIndex, sampler.Code, parent);
        }
        Expr uv = Coordinates(new JObject { ["CoordinateIndex"] = 0 });
        Expr rgba = ApplySamplerType(new Expr(TextureSample(context, new JObject(), sampler, uv.Code, false), 4), sampler);
        return output switch {
            0 => Swizzle(rgba, "rgb"), 1 => Swizzle(rgba, "r"), 2 => Swizzle(rgba, "g"),
            3 => Swizzle(rgba, "b"), 4 => Swizzle(rgba, "a"),
            _ => throw new ConversionException($"Invalid font sample output {output}")
        };
    }

    private string? FontParameterPage(JToken? value)
    {
        if (value is not JObject parameter) return null;
        string font = parameter.Value<string>("font")!;
        var asset = library.Load(font);
        string? page = FontPage(asset, font, parameter.Value<int>("page"), false);
        if (page is not null) CaptureFontPages(asset, font);
        return page;
    }

    private void CaptureFontPages(SourceAsset asset, string path)
    {
        if (fontAssets.ContainsKey(path)) return;
        var font = asset.ResolveObject(path, value => value.ClassName == "Font");
        var catalog = new Dictionary<int, string>();
        if (font.Properties["Textures"] is JArray pages) {
            for (int page = 0; page < pages.Count; page++) {
                int reference = pages[page].Value<int>();
                if (reference == 0) continue;
                string texture = asset.ObjectPath(reference);
                FontTextureSettings(texture);
                catalog.Add(page, texture);
            }
        }
        fontAssets.Add(path, catalog);
    }

    private static string? FontPage(SourceAsset asset, string path, int page, bool expressionDefault)
    {
        var font = asset.ResolveObject(path, value => value.ClassName == "Font");
        if (expressionDefault) {
            string cache = (font.Properties.Value<string>("FontCacheType") ?? "Offline").Split("::").Last();
            if (cache is not ("Offline" or "0"))
                throw new ConversionException($"Material font sampling requires an offline font: {path} ({cache})");
        }
        if (font.Properties["Textures"] is not JArray pages || page < 0 || page >= pages.Count) return null;
        int reference = pages[page].Value<int>();
        return reference != 0 ? asset.ObjectPath(reference) : expressionDefault ? DefaultMaterialTexture2D : null;
    }

    private TextureSettings FontTextureSettings(string path)
    {
        var asset = library.Load(path);
        var texture = asset.ResolveObject(path, value => value.ClassName == "Texture2D");
        if (texture.Properties.Value<bool?>("VirtualTextureStreaming") == true)
            throw new ConversionException($"Material font page cannot use a virtual texture sampler: {path}");
        ReadTextureSettings(path);
        var settings = textureSettings[path];
        if (!settings.FontPage) textureSettings[path] = settings = settings with { FontPage = true };
        return settings;
    }
}
