using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Normalizes the system's saved script map while retaining the cooked execution layout. */
internal static class ScriptRuntimeSource
{
    internal const string ExecutionStore = "CookedExecutionParameterStore";
    internal const string RapidBindings = "CookedRapidIterationBindings";

    internal static JArray Read(SourceAsset asset, SourceObject system)
    {
        if (system.Properties["ScriptRuntimeCookedDataMap"] is JArray { Count: > 0 } cooked) {
            JArray result = new();
            foreach (JToken entry in cooked) {
                if (entry is not JArray { Count: 2 } pair || pair[0] is not JObject key || pair[1] is not JObject value)
                    throw new ConversionException($"Invalid cooked Niagara script map entry: {system.Name}");
                JObject runtime = (JObject)(value["CookedScriptRuntimeCompiledData"]?.DeepClone() ?? new JObject());
                runtime[ExecutionStore] = value[ExecutionStore]?.DeepClone() ?? new JObject();
                runtime[RapidBindings] = value[RapidBindings]?.DeepClone() ?? new JArray();
                JObject identity = (JObject)key.DeepClone();
                identity["EmitterHandleId"] ??= Guid.Empty.ToString("B");
                identity["ScriptUsageId"] ??= Guid.Empty.ToString("B");
                identity["ScriptUsage"] ??= "Function";
                result.Add(new JArray(identity, runtime));
            }
            return result;
        }
        int index = system.Properties.Value<int?>("ScriptRuntimeCompiledDataForEditor") ?? 0;
        if (index != 0)
            return asset.Object(index).Properties["ScriptRuntimeCompiledDataMap"] as JArray ?? new();
        return FromLegacyScripts(asset, system);
    }

    private static JArray FromLegacyScripts(SourceAsset asset, SourceObject system)
    {
        JArray result = new();
        JObject properties = system.Properties;
        string emptyGuid = Guid.Empty.ToString("B").ToUpperInvariant();
        void AddScript(string emitterHandleId, int scriptIndex)
        {
            if (scriptIndex <= 0) return;
            SourceObject script = asset.Object(scriptIndex);
            JObject scriptProps = script.Properties;
            string? usage = scriptProps.Value<string>("Usage");
            if (string.IsNullOrEmpty(usage)) return;
            string usageId = scriptProps.Value<string>("UsageId") ?? emptyGuid;
            JObject identity = new() {
                ["EmitterHandleId"] = emitterHandleId,
                ["ScriptUsage"] = usage,
                ["ScriptUsageId"] = usageId
            };
            JObject runtime = new() {
                ["ResolvedDataInterfaces"] = scriptProps["ResolvedDataInterfaces"]?.DeepClone() ?? new JArray(),
                ["ResolvedUObjects"] = scriptProps["ResolvedUObjects"]?.DeepClone() ?? new JArray()
            };
            result.Add(new JArray(identity, runtime));
        }

        AddScript(emptyGuid, properties.Value<int?>("SystemSpawnScript") ?? 0);
        AddScript(emptyGuid, properties.Value<int?>("SystemUpdateScript") ?? 0);
        foreach (JObject handle in (properties["EmitterHandles"] as JArray ?? new()).OfType<JObject>())
        {
            if (handle.Value<bool?>("bIsEnabled") == false) continue;
            string handleId = handle.Value<string>("Id") ?? emptyGuid;
            EmitterHandleSource.Resolved resolvedEmitter = EmitterHandleSource.Resolve(asset, handle);
            if (resolvedEmitter.Stateless) continue;
            JObject version = resolvedEmitter.Settings;
            AddScript(handleId, version["SpawnScriptProps"]?.Value<int?>("Script") ?? 0);
            AddScript(handleId, version["UpdateScriptProps"]?.Value<int?>("Script") ?? 0);
            AddScript(handleId, version.Value<int?>("GPUComputeScript") ?? 0);
            foreach (JObject handler in (version["EventHandlerScriptProps"] as JArray ?? new()).OfType<JObject>())
                AddScript(handleId, handler.Value<int?>("Script") ?? 0);
            foreach (int stageIndex in (version["SimulationStages"] as JArray ?? new()).Values<int>())
            {
                if (stageIndex > 0)
                    AddScript(handleId, asset.Object(stageIndex).Properties.Value<int?>("Script") ?? 0);
            }
        }
        return result;
    }
}
