using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** GPU emitters retain their spawn, update and event parameter contexts without executing CPU particle bytecode. */
internal static class GpuHostContextCompiler
{
    internal static JArray Compile(SourceAsset asset, SourceObject system, JObject emitter, string handle,
        JArray runtimes, AssetLibrary? library)
    {
        JArray result = new();
        Add(emitter["SpawnScriptProps"]!.Value<int>("Script"), -1);
        Add(emitter["UpdateScriptProps"]!.Value<int>("Script"), -1);
        int eventIndex = 0;
        foreach (JObject handler in emitter["EventHandlerScriptProps"] as JArray ?? new())
            Add(handler.Value<int?>("Script") ?? 0, eventIndex++);
        return result;

        void Add(int reference, int eventSlot) {
            if (reference == 0 && eventSlot >= 0) {
                result.Add(new JObject { ["usage"] = null, ["usage_id"] = null, ["event_index"] = eventSlot,
                    ["execution_store"] = new JObject { ["variables"] = new JArray(), ["data"] = "",
                        ["current_bytes"] = 0, ["interpolated"] = false, ["references"] = new JArray(),
                        ["initial_sources"] = new JArray(), ["instance_sources"] = new JArray(), ["instance_bindings"] = new JArray(),
                        ["interface_values"] = new JArray(), ["object_values"] = new JArray() },
                    ["collections"] = new JArray() });
                return;
            }
            SourceObject script = asset.Object(reference);
            JObject runtime = VectorProgramCompiler.ResolvedRuntime(runtimes, handle, script);
            JObject store = ExecutionParameterStoreSource.Compile(asset, script, runtime, library, system);
            NiagaraCollectionSource collections = new(asset, system, script.Properties, library);
            JArray bindings = new();
            foreach (JObject variable in (JArray)store["variables"]!) {
                if (variable.Value<string>("storage") != "numeric" || variable.Value<int>("bytes") == 0
                        || variable.Value<int>("offset") >= store.Value<int>("current_bytes")) continue;
                JObject binding = new() { ["name"] = variable["name"]!.DeepClone(), ["source_type"] = variable["source_type"]!.DeepClone() };
                collections.Apply(binding, variable.Value<string>("name")!, variable.Value<string>("source_type")!, variable.Value<int>("bytes"));
                if (binding.ContainsKey("collection")) bindings.Add(binding);
            }
            result.Add(new JObject { ["usage"] = script.Properties["Usage"]?.DeepClone(),
                ["usage_id"] = script.Properties["UsageId"]?.DeepClone() ?? JToken.FromObject(Guid.Empty.ToString("B")), ["event_index"] = eventSlot,
                ["execution_store"] = store, ["collections"] = bindings });
        }
    }
}
