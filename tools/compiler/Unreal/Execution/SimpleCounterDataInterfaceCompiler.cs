using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SimpleCounterDataInterfaceCompiler
{
    internal static JObject Definition(SourceObject data)
        => Definition(data.Properties);

    internal static JObject Definition(JObject properties)
    {
        string mode = (properties.Value<string>("GpuSyncMode") ?? "None").Split("::").Last();
        if (mode is not ("None" or "SyncCpuToGpu" or "SyncGpuToCpu" or "SyncBoth"))
            throw new ConversionException($"Unknown Niagara counter synchronization mode {mode}");
        return new JObject { ["initial"] = properties.Value<int?>("InitialValue") ?? 0, ["gpu_sync_mode"] = mode };
    }

    internal static JObject Compile(SourceObject data, string function, int inputs, int outputs)
        => Compile(data.Properties, function, inputs, outputs);

    internal static JObject Compile(JObject properties, string function, int inputs, int outputs)
    {
        bool valid = function switch {
            "GetNextValue" or "Get" => inputs == 0 && outputs == 1,
            "Set" => inputs == 2 && outputs == 0,
            "Exchange" => inputs == 2 && outputs == 1,
            "Add" => inputs == 2 && outputs == 2,
            "Increment" or "Decrement" => inputs == 1 && outputs == 2,
            _ => false };
        if (!valid) throw new ConversionException($"Invalid Niagara counter signature {function}: {inputs} inputs, {outputs} outputs");
        return Definition(properties);
    }
}
