using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Unsigned seeds and cached offsets retain the source copy and duplicate initialization rules. */
internal static class CurlNoiseDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceCurlNoise";

    internal static void Validate(string function, int inputs, int outputs)
    {
        if (!SourceNameOrder.Equal(function, "SampleNoiseField") || inputs != 3 || outputs != 3)
            throw new ConversionException($"Invalid curl noise function signature: {function} ({inputs}, {outputs})");
    }

    internal static JObject Compile(SourceAsset asset, int reference, AssetLibrary? library, string initialization)
    {
        if (initialization is not ("loaded" or "reference" or "copy" or "duplicate"))
            throw new ConversionException($"Invalid curl noise initialization: {initialization}");
        SourceObject? source = DataInterfaceInstanceSources.Resolve(asset, reference, ClassName, library);
        string flags = source?.Raw.Value<string>("ObjectFlags") ?? "";
        bool classDefault = source is null || flags.Contains("RF_ClassDefaultObject", StringComparison.Ordinal);
        bool archetype = source is null || flags.Contains("RF_ArchetypeObject", StringComparison.Ordinal);
        uint seed = source?.Properties.Value<uint?>("Seed") ?? 0u;
        uint offsetSeed = initialization == "duplicate" ? archetype ? 0u : seed : classDefault ? 0u : seed;
        return new JObject { ["seed"] = unchecked((int)seed), ["offset_seed"] = unchecked((int)offsetSeed) };
    }
}
