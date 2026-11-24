using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NiagaraDebugParametersSource
{
    internal static JObject Compile(SourceAsset asset, SourceObject system, SourceObject spawn, SourceObject update)
    {
        var values = new JArray();
        var seen = new HashSet<(string Name, string Type, int Underlying, int Flags)>();
        foreach (var script in new[] { spawn, update })
        {
            if (script.Properties["CachedScriptVM"]?["StaticVariablesWritten"] is not JArray variables) continue;
            foreach (JObject variable in variables)
            {
                string name = variable.Value<string>("name") ?? throw new ConversionException("Static variable has no name");
                var type = NiagaraTypeSource.Read(asset, variable["type"] as JObject
                    ?? throw new ConversionException($"Static variable has no type: {name}"));
                if (!seen.Add((name.ToLowerInvariant(), type.Path.ToLowerInvariant(), type.UnderlyingType, type.Flags))) continue;
                if (type.UnderlyingType == 1)
                    throw new ConversionException($"Object type is invalid for a static Niagara value: {name}");
                string data = variable.Value<string>("data") ?? throw new ConversionException($"Static variable has no data: {name}");
                values.Add(new JObject { ["name"] = name, ["type"] = type.Path, ["underlying"] = type.UnderlyingType,
                    ["flags"] = type.Flags, ["data"] = data });
            }
        }
        var handles = (JArray)system.Properties["EmitterHandles"]!;
        return new JObject {
            ["emitter_names"] = new JArray(handles.Select(handle => handle.Value<string>("Name")
                ?? throw new ConversionException("Niagara emitter handle has no source name"))),
            ["static_values"] = values
        };
    }
}
