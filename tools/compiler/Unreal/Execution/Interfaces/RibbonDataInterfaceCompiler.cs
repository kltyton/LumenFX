using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class RibbonDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceRibbon";

    internal static void Validate(SourceAsset asset, JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        (int Width, string Type) point = name switch {
            "SetRibbonShape" => (2, "Vector2f"),
            "SetRibbonShapeFull" => (5, "NiagaraRibbonShapePoint"),
            _ => throw new ConversionException($"Unknown Niagara Ribbon Interface function {name}")
        };
        JArray positions = function["VariadicInputs"] as JArray ?? new();
        if (outputs != 0 || inputs != 1 + positions.Count * point.Width || positions.Any(value =>
                value["type"] is not JObject type || !NiagaraTypeSource.Read(asset, type).Path.EndsWith("." + point.Type, StringComparison.Ordinal)))
            throw new ConversionException($"Invalid Niagara Ribbon Interface signature: {name} ({inputs}, {outputs})");
    }
}
