using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Static values follow the system's native script visitation order, including repeated writes. */
internal sealed class RendererStaticVariables(SourceAsset asset, SourceObject system)
{
    internal readonly record struct Variable(string Name, bool Value);
    private Variable[]? values;
    internal IReadOnlyList<Variable> Values => values ??= Read().ToArray();

    private IEnumerable<Variable> Read()
    {
        foreach (int index in Scripts())
        {
            if (index == 0) continue;
            JArray variables = asset.Object(index).Properties["CachedScriptVM"]?["StaticVariablesWritten"] as JArray ?? new JArray();
            foreach (JObject variable in variables)
            {
                JObject type = variable["type"] as JObject ?? throw new ConversionException("Static variable has no type");
                var loaded = NiagaraTypeSource.Read(asset, type);
                if (loaded.Flags != 1 || loaded.UnderlyingType != 2
                        || !SourceNameOrder.Equal(loaded.Path, "/Script/Niagara.NiagaraBool")) continue;
                string name = variable.Value<string>("name") ?? throw new ConversionException("Static variable has no name");
                byte[] data = Convert.FromBase64String(variable.Value<string>("data")
                    ?? throw new ConversionException($"Static bool has no value: {name}"));
                if (data.Length != 4) throw new ConversionException($"Static Niagara bool requires four bytes: {name}");
                yield return new Variable(name, data[0] != 0);
            }
        }
    }

    private IEnumerable<int> Scripts()
    {
        JObject properties = system.Properties;
        yield return properties.Value<int>("SystemSpawnScript");
        yield return properties.Value<int>("SystemUpdateScript");
        foreach (JObject handle in (JArray)properties["EmitterHandles"]!)
        {
            if (handle.Value<bool?>("bIsEnabled") == false || !EmitterHandleSource.IsStandard(handle)) continue;
            JObject reference = (JObject)handle["VersionedInstance"]!;
            SourceObject emitter = asset.Object(reference.Value<int>("Emitter"));
            JObject? version = EmitterVersionSource.Find(emitter, reference.Value<string>("Version"));
            if (version is null) continue;
            yield return version["SpawnScriptProps"]?.Value<int>("Script") ?? 0;
            yield return version["UpdateScriptProps"]?.Value<int>("Script") ?? 0;
            int gpu = version.Value<int?>("GPUComputeScript") ?? 0;
            if (gpu != 0) yield return gpu;
            if (version["EventHandlerScriptProps"] is JArray events)
                foreach (JObject entry in events) yield return entry.Value<int>("Script");
        }
    }
}
