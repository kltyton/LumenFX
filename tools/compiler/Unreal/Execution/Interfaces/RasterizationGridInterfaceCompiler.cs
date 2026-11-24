using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class RasterizationGridInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceRasterizationGrid3D";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        var cells = properties?["NumCells"];
        return new JObject {
            ["cells"] = new JArray(cells?.Value<int?>("X") ?? 3, cells?.Value<int?>("Y") ?? 3, cells?.Value<int?>("Z") ?? 3),
            ["attributes"] = properties?.Value<int?>("NumAttributes") ?? 1,
            ["precision"] = SourceAsset.Number(properties?["Precision"], 1),
            ["reset_value"] = properties?.Value<int?>("ResetValue") ?? 0,
            ["clear_output"] = properties?.Value<bool?>("ClearBeforeNonIterationStage") ?? true
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetNumCells" => (0, 3),
            "SetNumCells" => (3, 1),
            "SetNumAttributes" or "SetFloatResetValue" => (1, 1),
            _ => throw new ConversionException($"Unknown Niagara Rasterization Grid3D CPU function {name}")
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Rasterization Grid3D signature: {name} ({inputs}, {outputs})");
    }
}
