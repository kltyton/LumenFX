using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Volumes;

internal static class VolumeRendererCompiler
{
    internal static JObject Compile(JObject properties, string variant)
    {
        var binding = properties["MaterialParameterBinding"]?["ResolvedParameter"];
        string? parameter = binding?.Value<string>("name") ?? binding?.Value<string>("Name");
        return new JObject {
            ["step_factor"] = SourceAsset.Number(properties["StepFactor"], 1),
            ["shadow_step_factor"] = SourceAsset.Number(properties["ShadowStepFactor"], 2),
            ["shadow_bias_factor"] = SourceAsset.Number(properties["ShadowBiasFactor"], .5),
            ["lighting_downsample_factor"] = SourceAsset.Number(properties["LightingDownsampleFactor"], 2),
            ["material_binding"] = string.IsNullOrEmpty(parameter) || parameter == "None" ? null : parameter,
            ["material_variant"] = variant
        };
    }
}
