using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class VolumeCacheInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceVolumeCache";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        return new JObject {
            ["asset_source"] = ObjectPropertyReaderCompiler.ObjectPath(source?.Asset ?? owner, source?.Object?.Properties["VolumeCache"])
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "SetFrame" or "ReadFile" => (1, 1),
            "GetNumCells" => (0, 3),
            _ => throw new ConversionException($"Niagara Volume Cache function is not bound by the CPU source: {name}")
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Volume Cache signature: {name} ({inputs}, {outputs})");
    }
}
