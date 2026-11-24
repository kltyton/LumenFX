using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Reconstructs the editor execution stores used to discover physical render-target instances. */
internal static class RenderTarget2DInstancesCompiler
{
    internal static JArray Compile(SourceAsset asset, SourceObject system, JArray runtimes, AssetLibrary? library)
        => Compile(asset, system, DataInterfaceInstanceSources.Collect(asset, system, runtimes, library), library);

    internal static JArray Compile(SourceAsset asset, SourceObject system,
        IReadOnlyList<DataInterfaceInstanceSources.Entry> instances, AssetLibrary? library)
    {
        Dictionary<string, JObject> discovered = new(StringComparer.Ordinal);
        JArray result = new();
        foreach (var source in instances) {
            var parameter = source.Parameter;
            int reference = parameter.Reference;
            if (reference == 0) continue;
            SourceAsset objectAsset = parameter.ObjectAsset ?? asset;
            string type = objectAsset.ReferencedClass(reference);
            string path = objectAsset.ObjectPath(reference);
            if (type != "NiagaraDataInterfaceRenderTarget2D" && !path.EndsWith(".Default__NiagaraDataInterfaceRenderTarget2D", StringComparison.Ordinal)) continue;
            string id = parameter.InstanceId ?? ScriptDataInterfaceBindings.Identity(objectAsset, reference);
            if (!discovered.TryGetValue(id, out JObject? descriptor)) {
                descriptor = new JObject { ["interface_id"] = id, ["class"] = "NiagaraDataInterfaceRenderTarget2D",
                    ["used_cpu"] = false, ["used_gpu"] = false,
                    ["data"] = RenderTarget2DDataInterfaceCompiler.Compile(asset, reference > 0 && objectAsset == asset ? objectAsset.Object(reference) : null,
                            system, library, reference < 0 || objectAsset != asset ? path : null) };
                discovered.Add(id, descriptor); result.Add(descriptor);
            }
            descriptor["used_cpu"] = descriptor.Value<bool>("used_cpu") || source.Cpu;
            descriptor["used_gpu"] = descriptor.Value<bool>("used_gpu") || source.Gpu;
        }
        return result;
    }

}
