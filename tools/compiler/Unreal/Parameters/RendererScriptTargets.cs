using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Script identities and visitation order used when rebuilding one emitter's renderer store. */
internal static class RendererScriptTargets
{
    internal sealed record Target(SourceObject Script, JObject? Runtime);

    internal static IEnumerable<Target> Read(SourceAsset asset, SourceObject system, JObject emitter, string? handle)
    {
        if (!Guid.TryParse(handle, out Guid emitterId))
            throw new ConversionException("Renderer store rebuild requires its emitter handle ID");
        JArray runtimes = ScriptRuntimeSource.Read(asset, system);
        foreach (string field in new[] { "SystemSpawnScript", "SystemUpdateScript" }) {
            int index = system.Properties.Value<int?>(field) ?? 0;
            if (index != 0) yield return Resolve(index, Guid.Empty);
        }
        foreach (int index in EmitterScripts(asset, emitter)) if (index != 0) yield return Resolve(index, emitterId);

        Target Resolve(int index, Guid owner) {
            SourceObject script = asset.Object(index);
            string usage = script.Properties.Value<string>("Usage")
                ?? throw new ConversionException("Renderer script target has no usage");
            if (!Guid.TryParse(script.Properties.Value<string>("UsageId") ?? Guid.Empty.ToString(), out Guid usageId))
                throw new ConversionException("Renderer script target has no valid usage ID");
            JObject? runtime = null;
            foreach (JArray pair in runtimes) {
                if (pair.Count != 2 || pair[0] is not JObject key || pair[1] is not JObject value)
                    throw new ConversionException("Invalid renderer script runtime map entry");
                if (!Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid entryOwner)
                        || !Guid.TryParse(key.Value<string>("ScriptUsageId"), out Guid entryUsageId))
                    throw new ConversionException("Renderer script runtime key has no valid identity");
                if (entryOwner != owner || entryUsageId != usageId || key.Value<string>("ScriptUsage") != usage) continue;
                if (runtime is not null) throw new ConversionException("Multiple runtime entries select a renderer script target");
                runtime = value;
            }
            return new(script, runtime);
        }
    }

    private static IEnumerable<int> EmitterScripts(SourceAsset asset, JObject emitter)
    {
        foreach (string field in new[] { "SpawnScriptProps", "UpdateScriptProps", "EmitterSpawnScriptProps", "EmitterUpdateScriptProps" })
            yield return emitter[field]?.Value<int?>("Script") ?? 0;
        foreach (JObject handler in (emitter["EventHandlerScriptProps"] as JArray ?? new()).OfType<JObject>())
            yield return handler.Value<int?>("Script") ?? 0;
        foreach (int index in (emitter["SimulationStages"] as JArray ?? new()).Values<int>()) {
            if (index == 0) continue;
            JObject stage = asset.Object(index).Properties;
            if (stage.Value<bool?>("bEnabled") == false) continue;
            yield return stage.Value<int?>("Script") ?? 0;
        }
        if (EmitterHandleSource.SimulationTarget(emitter) == "GPUComputeSim")
            yield return emitter.Value<int?>("GPUComputeScript") ?? 0;
    }
}
