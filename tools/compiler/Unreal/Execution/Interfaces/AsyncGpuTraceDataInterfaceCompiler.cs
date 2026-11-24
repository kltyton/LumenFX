using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class AsyncGpuTraceDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceAsyncGpuTrace";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        int maxTraces = properties?.Value<int?>("MaxTracesPerParticle") ?? 1;
        int maxRetraces = properties?.Value<int?>("MaxRetraces") ?? 0;
        int provider = properties?["TraceProvider"] switch {
            null => 0,
            { Type: JTokenType.Integer } value => value.Value<int>(),
            { Type: JTokenType.String } value => value.Value<string>()?.Split("::").Last() switch {
                "Default" => 0, "HWRT" => 1, "GSDF" => 2, "None" => 3,
                _ => throw new ConversionException("Unknown Niagara async GPU trace provider")
            },
            _ => throw new ConversionException("Invalid Niagara async GPU trace provider")
        };
        if (maxTraces < 0 || maxRetraces < 0 || provider is < 0 or > 3)
            throw new ConversionException("Invalid Niagara async GPU trace configuration");
        return new JObject { ["max_traces_per_particle"] = maxTraces, ["max_retraces"] = maxRetraces, ["provider"] = provider };
    }
}
