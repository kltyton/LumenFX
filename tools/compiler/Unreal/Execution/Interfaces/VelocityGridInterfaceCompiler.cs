using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class VelocityGridInterfaceCompiler
{
    internal const string VelocityClass = "NiagaraDataInterfaceVelocityGrid";
    internal const string PressureClass = "NiagaraDataInterfacePressureGrid";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library, string type)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, type, library);
        var size = source?.Object?.Properties["GridSize"];
        return new JObject {
            ["size"] = new JArray(size?.Value<int?>("X") ?? 10, size?.Value<int?>("Y") ?? 10, size?.Value<int?>("Z") ?? 10),
            ["pressure"] = type == PressureClass
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "SetGridDimension" => (3, 1),
            "UpdateGridTransform" => (16, 1),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Velocity/Pressure Grid CPU function {name} ({inputs}, {outputs})");
    }
}
