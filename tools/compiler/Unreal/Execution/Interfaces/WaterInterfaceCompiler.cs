using LumenFx.Compiler.Unreal.Scene;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class WaterInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceWater";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        string path = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["SourceBodyComponent"]);
        return new JObject {
            ["source"] = path, ["saved_body"] = WaterBodySource.Compile(asset, path, library),
            ["find_closest"] = properties.Value<bool>("bFindWaterBodyOnSpawn"),
            ["evaluate_depth"] = properties.Value<bool?>("bEvaluateSystemDepth") ?? true,
            ["evaluate_depth_per_frame"] = properties.Value<bool?>("bEvaluateSystemDepthPerFrame") ?? true
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "IsValid" or "GetWaveParamLookupTableOffset" => (0, 1),
            "GetWaterDataAtPoint" => (4, 11),
            "GetWaterSurfaceInfo" => (7, 14),
            _ => (-1, -1)
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Water CPU function {name} ({inputs}, {outputs})");
    }
}
