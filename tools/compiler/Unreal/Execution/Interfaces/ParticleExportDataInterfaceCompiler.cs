using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ParticleExportDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceExport";

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        if (name is not ("StoreParticleData" or "ExportParticleData") || inputs != 8 || outputs != 1)
            throw new ConversionException($"Invalid Niagara particle export signature: {name} ({inputs}, {outputs})");
    }

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var properties = reference == 0 ? null : DataInterfaceInstanceSources.Resolve(owner, reference, ClassName, library)?.Properties;
        var mode = properties?["GPUAllocationMode"] switch {
            null => false,
            { Type: JTokenType.Integer } number when number.Value<int>() is 0 or 1 => number.Value<int>() == 1,
            { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "FixedSize" => false,
            { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "PerParticle" => true,
            _ => throw new ConversionException("Unknown Niagara particle export allocation mode")
        };
        return new JObject {
            ["parameter"] = properties?["CallbackHandlerParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["per_particle"] = mode,
            ["fixed_count"] = properties?.Value<int?>("GPUAllocationFixedSize") ?? 64,
            ["multiplier"] = properties?.Value<float?>("GPUAllocationPerParticleSize") ?? 1f
        };
    }
}
