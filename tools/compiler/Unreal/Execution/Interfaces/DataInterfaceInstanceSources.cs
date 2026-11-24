using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Visits the parameter stores in the system's DI-instance discovery order. */
internal static class DataInterfaceInstanceSources
{
    internal sealed record Entry(ScriptDataInterfaceBindings.Parameter Parameter, bool Cpu, bool Gpu, Guid Emitter);
    internal sealed record ObjectSource(SourceAsset Asset, SourceObject? Object);

    internal static SourceObject? Resolve(SourceAsset owner, int reference, string type, AssetLibrary? library)
        => ResolveSource(owner, reference, type, library).Object;

    internal static string MeshSourceActor(SourceAsset asset, JObject properties)
    {
        string legacy = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["Source_DEPRECATED"]);
        return legacy.Length == 0 ? ObjectPropertyReaderCompiler.ObjectPath(asset, properties["SoftSourceActor"]) : legacy;
    }

    internal static ObjectSource ResolveSource(SourceAsset owner, int reference, string type, AssetLibrary? library)
    {
        if (reference == 0) throw new ConversionException($"Data interface is null: {owner.Name} ({type})");
        if (reference > 0) return new(owner, owner.Object(reference));
        string path = owner.ObjectPath(reference);
        if (path.StartsWith("/Script/", StringComparison.Ordinal)
                && path.EndsWith(".Default__" + type, StringComparison.Ordinal)) return new(owner, null);
        SourceAsset target = (library ?? owner.Library)?.Load(path)
            ?? throw new ConversionException($"Data interface requires its source library: {path}");
        return new(target, target.ResolveObject(path, value => value.ClassName == type));
    }

    internal static IReadOnlyList<Entry> Collect(SourceAsset asset, SourceObject system, JArray runtimes, AssetLibrary? library)
    {
        JObject properties = system.Properties;
        List<Entry> result = new();
        Visit(ScriptDataInterfaceBindings.InstanceStore(asset, system), false, false, Guid.Empty);
        VisitScript(Guid.Empty, asset.Object(properties.Value<int>("SystemSpawnScript")), true, false, true);
        VisitScript(Guid.Empty, asset.Object(properties.Value<int>("SystemUpdateScript")), true, false, true);
        foreach (JObject handle in properties["EmitterHandles"] as JArray ?? new()) {
            if (handle.Value<bool?>("bIsEnabled") == false || !EmitterHandleSource.IsStandard(handle)) continue;
            if (!Guid.TryParse(handle.Value<string>("Id"), out Guid id)) throw new ConversionException("Emitter handle has no valid identity");
            var emitter = EmitterHandleSource.Resolve(asset, handle);
            JObject version = emitter.Settings;
            bool gpu = emitter.Target == "GPUComputeSim";
            VisitScript(id, asset.Object(version["SpawnScriptProps"]!.Value<int>("Script")), !gpu, gpu, false);
            VisitScript(id, asset.Object(version["UpdateScriptProps"]!.Value<int>("Script")), !gpu, gpu, false);
            if (!gpu) foreach (JObject handler in version["EventHandlerScriptProps"] as JArray ?? new()) {
                int reference = handler.Value<int?>("Script") ?? 0;
                if (reference > 0) VisitScript(id, asset.Object(reference), true, false, false);
            }
            if (gpu) VisitScript(id, asset.Object(version.Value<int>("GPUComputeScript")), false, true, false);
        }
        return result;

        void VisitScript(Guid emitter, SourceObject script, bool cpu, bool gpu, bool searchInstance)
        {
            string usage = script.Properties.Value<string>("Usage") ?? throw new ConversionException("Script has no usage");
            if (!Guid.TryParse(script.Properties.Value<string>("UsageId") ?? Guid.Empty.ToString(), out Guid usageId))
                throw new ConversionException("Script has no valid usage identity");
            JObject[] matches = runtimes.OfType<JArray>().Where(pair => pair.Count == 2 && pair[0] is JObject key
                    && Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid owner) && owner == emitter
                    && key.Value<string>("ScriptUsage") == usage
                    && Guid.TryParse(key.Value<string>("ScriptUsageId"), out Guid candidate) && candidate == usageId)
                .Select(pair => (JObject)pair[1]).ToArray();
            if (matches.Length == 0) return;
            if (matches.Length != 1) throw new ConversionException("Execution store has multiple compiled runtime records");
            Visit(ScriptDataInterfaceBindings.Store(asset, system, script, matches[0], searchInstance, library), cpu, gpu, emitter);
        }

        void Visit(IEnumerable<ScriptDataInterfaceBindings.Parameter> store, bool cpu, bool gpu, Guid emitter)
        {
            foreach (var parameter in store.OrderBy(value => value.Variable.Value<string>("name")
                        ?? throw new ConversionException("Interface variable has no name"), SourceNameOrder.Instance)
                    .ThenBy(value => ScriptDataInterfaceBindings.TypeName(asset, value.Variable), SourceNameOrder.Instance))
                if (parameter.Reference != 0) result.Add(new(parameter, cpu, gpu, emitter));
        }
    }
}
