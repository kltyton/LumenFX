using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const string FortniteMainVersion = "{697DD581-E64F-41AB-AA4A-51ECBEB7B628}";
    private const int MaterialPixelDepthOffsetModeVersion = 153;

    private static bool UsesCameraVectorDepthOffset(SourceAsset asset, SourceObject material)
    {
        int version = (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
            .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), FortniteMainVersion, StringComparison.OrdinalIgnoreCase))
            ?.Value<int>("Version") ?? -1;
        if (version < MaterialPixelDepthOffsetModeVersion) return false;
        return (material.Properties.Value<string>("PixelDepthOffsetMode") ?? "PDOM_AlongCameraVector").Split("::").Last() switch {
            "PDOM_Legacy" or "0" => false,
            "PDOM_AlongCameraVector" or "1" => true,
            var mode => throw new ConversionException($"Unknown pixel depth offset mode {mode}: {asset.Name}:{material.Name}")
        };
    }
}
