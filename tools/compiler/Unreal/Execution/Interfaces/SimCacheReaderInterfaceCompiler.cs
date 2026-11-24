using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SimCacheReaderInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceSimCacheReader";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        var binding = properties?["SimCacheBinding"] as JObject;
        var parameter = binding?["Parameter"] as JObject;
        return new JObject {
            ["asset_source"] = ObjectPropertyReaderCompiler.ObjectPath(source?.Asset ?? owner, properties?["SimCache"]),
            ["user_parameter"] = parameter?.Value<string>("name") ?? parameter?.Value<string>("Name") ?? "",
            ["emitter"] = properties?.Value<string>("EmitterBinding") ?? ""
        };
    }

    internal static JObject CpuFunction(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        var shape = name switch {
            "GetNumFrames" or "GetNumEmitters" or "GetEmitterIndex" => (0, 1),
            "GetNumInstances" => (1, 1),
            "GetInstanceInt" or "GetInstanceFloat" => (2, 2),
            "GetInstanceVector2" or "GetInstanceID" => (2, 3),
            "GetInstanceVector3" or "GetInstancePosition" => (2, 4),
            "GetInstanceVector4" or "GetInstanceColor" or "GetInstanceQuat" => (2, 5),
            "GetInstanceIndexFromID" => (3, 2),
            _ => throw new ConversionException($"Unknown Niagara SimCache Reader function {name}")
        };
        if (shape != (inputs, outputs)) throw new ConversionException($"Invalid Niagara SimCache Reader signature: {name} ({inputs}, {outputs})");
        if (inputs < 2) return new JObject();
        string? attribute = (function["FunctionSpecifiers"] as JArray ?? new()).OfType<JObject>()
            .SingleOrDefault(value => SourceNameOrder.Equal(value.Value<string>("Key") ?? "", "Attribute"))?.Value<string>("Value");
        if (string.IsNullOrEmpty(attribute))
            throw new ConversionException($"Niagara SimCache Reader function {name} has no saved Attribute specifier");
        return new JObject { ["attribute"] = attribute };
    }

    internal static JArray GpuFunctions(JObject shaderInterface)
    {
        var entries = new JArray();
        foreach (var item in shaderInterface["GeneratedFunctions"] as JArray ?? new JArray()) {
            if (item is not JObject function || function.Value<string>("DefinitionName") is not string name)
                throw new ConversionException("Invalid generated Niagara SimCache Reader function");
            var specs = function["Specifiers"] as JArray;
            string attribute = specs?.Count == 1 ? specs[0]?.Value<string>("Value") ?? "" : "";
            entries.Add(new JObject { ["name"] = name, ["attribute"] = attribute });
        }
        return entries;
    }
}
