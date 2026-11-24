using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Arrays and counters need instance state even when no external function references their slots. */
internal static class MutableDataInterfaceInstancesCompiler
{
    internal static JArray Compile(SourceAsset asset, IReadOnlyList<DataInterfaceInstanceSources.Entry> instances, AssetLibrary? library,
        DataInterfaceGpuFunctions functions)
    {
        Dictionary<string, JObject> discovered = new(SourceNameOrder.Instance);
        JArray result = new();
        foreach (var entry in instances) {
            var parameter = entry.Parameter;
            SourceAsset owner = parameter.ObjectAsset ?? asset;
            int reference = parameter.Reference;
            string type = owner.ReferencedClass(reference);
            if (!ArrayDataInterfaceCompiler.Supports(type) && type != "NiagaraDataInterfaceSimpleCounter") continue;
            string identity = parameter.InstanceId ?? ScriptDataInterfaceBindings.Identity(owner, reference);
            if (!discovered.TryGetValue(identity, out JObject? descriptor)) {
                JObject properties = ReadProperties(owner, reference, type, library);
                JObject data = type == "NiagaraDataInterfaceSimpleCounter" ? SimpleCounterDataInterfaceCompiler.Definition(properties)
                    : ArrayDataInterfaceCompiler.Definition(type, properties, identity);
                if (ArrayDataInterfaceCompiler.Supports(type)) data["gpu_writable"] = functions.ArrayWritable(parameter);
                descriptor = new JObject { ["interface_id"] = identity, ["class"] = type,
                    ["parameter_name"] = parameter.Variable.Value<string>("name")
                        ?? throw new ConversionException("Mutable interface has no parameter name"),
                    ["used_cpu"] = false, ["used_gpu"] = false,
                    ["data"] = data };
                discovered.Add(identity, descriptor); result.Add(descriptor);
            }
            descriptor["used_cpu"] = descriptor.Value<bool>("used_cpu") || entry.Cpu;
            descriptor["used_gpu"] = descriptor.Value<bool>("used_gpu") || entry.Gpu;
        }
        return result;
    }

    internal static JObject ReadProperties(SourceAsset owner, int reference, string type, AssetLibrary? library)
        => DataInterfaceInstanceSources.Resolve(owner, reference, type, library)?.Properties ?? new();
}
