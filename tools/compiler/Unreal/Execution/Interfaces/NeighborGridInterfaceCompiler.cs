using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NeighborGridInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceNeighborGrid3D";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        var cells = properties?["NumCells"];
        var bounds = properties?["WorldBBoxSize"];
        return new JObject {
            ["resolution"] = (properties?.Value<string>("SetResolutionMethod") ?? "CellSize").Split("::").Last(),
            ["cells"] = new JArray(cells?.Value<int?>("X") ?? 3, cells?.Value<int?>("Y") ?? 3, cells?.Value<int?>("Z") ?? 3),
            ["cell_size"] = SourceAsset.Number(properties?["CellSize"], 1),
            ["max_axis"] = properties?.Value<int?>("NumCellsMaxAxis") ?? 10,
            ["neighbors"] = properties?.Value<int?>("MaxNeighborsPerCell") ?? 8,
            ["world_bounds"] = new JArray(SourceAsset.Number(bounds?["X"], 100), SourceAsset.Number(bounds?["Y"], 100),
                SourceAsset.Number(bounds?["Z"], 100))
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetNumCells" or "GetWorldBBoxSize" => (0, 3),
            "MaxNeighborsPerCell" => (0, 1),
            "SetNumCells" => (4, 1),
            _ => throw new ConversionException($"Unknown Niagara Neighbor Grid3D CPU function {name}")
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Neighbor Grid3D signature: {name} ({inputs}, {outputs})");
    }
}
