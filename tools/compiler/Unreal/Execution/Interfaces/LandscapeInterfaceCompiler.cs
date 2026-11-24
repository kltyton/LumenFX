using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class LandscapeInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceLandscape";
    private static readonly string[] Modes = ["Default", "Source", "AttachParent"];

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var asset = source?.Asset ?? owner;
        var properties = source?.Object?.Properties ?? new JObject();
        string mode = (properties.Value<string>("SourceMode") ?? Modes[0]).Split("::").Last();
        if (int.TryParse(mode, out int ordinal) && ordinal >= 0 && ordinal < Modes.Length) mode = Modes[ordinal];
        if (!Modes.Contains(mode, StringComparer.Ordinal))
            throw new ConversionException($"Invalid Niagara Landscape source mode: {mode}");
        return new JObject {
            ["source"] = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["SourceLandscape"]),
            ["mode"] = mode,
            ["virtual_textures"] = properties.Value<bool?>("bVirtualTexturesSupported") ?? true,
            ["physical_materials"] = new JArray((properties["PhysicalMaterials"] as JArray ?? new())
                .Select(value => ObjectPropertyReaderCompiler.ObjectPath(asset, value)))
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        if (name != "GenerateVirtualTextureFeedback" || inputs != 9 || outputs != 0)
            throw new ConversionException($"Invalid Niagara Landscape CPU function {name} ({inputs}, {outputs})");
    }

    internal static JObject GpuUsage(JObject shaderInterface)
    {
        var names = (shaderInterface["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>()
            .Select(function => function.Value<string>("DefinitionName")).ToHashSet(StringComparer.Ordinal);
        return new JObject {
            ["base_color"] = names.Contains("GetBaseColor"),
            ["height"] = names.Contains("GetHeight"),
            ["normal"] = names.Contains("GetWorldNormal"),
            ["material"] = names.Contains("GetPhysicalMaterialIndex")
        };
    }
}
