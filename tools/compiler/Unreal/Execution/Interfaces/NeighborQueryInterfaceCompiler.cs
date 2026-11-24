using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NeighborQueryInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceNeighborQuery";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        return new JObject {
            ["slots_per_particle"] = properties?.Value<long?>("MaxCellsPerParticle") ?? 1,
            ["count_only"] = properties?.Value<bool?>("bCountOnly") ?? false,
            ["persistent_ids"] = properties?.Value<bool?>("bUsePersistentIDs") ?? true
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetNumCells" => (0, 3),
            "MaxCellsPerParticle" => (0, 1),
            "SetNumCells" => (4, 1),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Neighbor Query function {name} ({inputs}, {outputs})");
    }
}
