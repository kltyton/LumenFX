using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class MemoryBufferInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceMemoryBuffer";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        return new JObject {
            ["default_elements"] = properties?.Value<int?>("DefaultNumElements") ?? 0,
            ["gpu_sync_mode"] = (properties?.Value<string>("GpuSyncMode") ?? "SyncCpuToGpu").Split("::").Last()
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "SetNumElements" => (1, 0), "GetNumElements" => (0, 1),
            "ClearBufferInt" or "ClearBufferFloat" => (2, 0),
            "LoadFloat" or "LoadInt" => (1, 2),
            "LoadFloat2" or "LoadInt2" => (1, 3),
            "LoadFloat3" or "LoadInt3" => (1, 4),
            "LoadFloat4" or "LoadInt4" => (1, 5),
            "StoreFloat" or "StoreInt" => (3, 1),
            "StoreFloat2" or "StoreInt2" => (4, 1),
            "StoreFloat3" or "StoreInt3" => (5, 1),
            "StoreFloat4" or "StoreInt4" => (6, 1),
            "AtomicAdd" or "AtomicAnd" or "AtomicMax" or "AtomicMin" or "AtomicOr" or "AtomicXor" => (3, 2),
            "AtomicCompareAndExchange" => (4, 2),
            _ => throw new ConversionException($"Unknown Niagara Memory Buffer function {name}")
        };
        if (shape != (inputs, outputs)) throw new ConversionException($"Invalid Niagara Memory Buffer signature: {name} ({inputs}, {outputs})");
    }
}
