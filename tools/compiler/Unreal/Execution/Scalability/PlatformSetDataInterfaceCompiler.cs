using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

/** Preserves PlatformSet predicates and the construction path of their GPU proxy. */
internal static class PlatformSetDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfacePlatformSet";
    internal const string DefaultObject = "/Script/Niagara.Default__NiagaraDataInterfacePlatformSet";

    internal static void Validate(string function, int inputs, int outputs)
    {
        if (!SourceNameOrder.Equal(function, "IsActive") || inputs != 0 || outputs != 1)
            throw new ConversionException($"Invalid PlatformSet function signature {function}: {inputs} inputs, {outputs} outputs");
    }
    internal static JObject Compile(SourceObject? data, AssetLibrary? library, string? reference = null,
        string initialization = "loaded")
    {
        if (initialization is not ("loaded" or "copy" or "duplicate"))
            throw new ConversionException($"Invalid PlatformSet initialization {initialization}");
        if (data == null && reference != DefaultObject) {
            if (reference is null) throw new ConversionException("Missing PlatformSet data interface object");
            SourceAsset owner = library?.Load(reference)
                ?? throw new ConversionException($"A source library is required for PlatformSet object {reference}");
            data = owner.ResolveObject(reference, value => value.ClassName == ClassName);
        }
        string flags = data?.Raw.Value<string>("ObjectFlags") ?? "";
        return new JObject {
            ["platforms"] = NiagaraScalabilitySource.Platform(data?.Properties["Platforms"]),
            ["class_default"] = data == null || flags.Contains("RF_ClassDefaultObject", StringComparison.Ordinal),
            ["archetype"] = data == null || flags.Contains("RF_ArchetypeObject", StringComparison.Ordinal),
            ["initialization"] = initialization
        };
    }
}
