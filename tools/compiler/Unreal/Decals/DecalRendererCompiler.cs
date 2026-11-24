using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Decals;

internal static class DecalRendererCompiler
{
    internal const string DefaultMaterial = "/Engine/EngineMaterials/DefaultDeferredDecalMaterial.DefaultDeferredDecalMaterial";
    internal static JObject Compile(JObject source, Func<string> defaultMaterial)
    {
        string? binding = source["MaterialParameterBinding"]?["ResolvedParameter"]?.Value<string>("name");
        if (string.IsNullOrEmpty(binding) || binding == "None") binding = null;
        return new JObject {
            ["screen_size_fade"] = SourceAsset.Number(source["DecalScreenSizeFade"]),
            ["material_binding"] = binding,
            ["invalid_domain_material"] = binding is null ? null : defaultMaterial()
        };
    }
}
