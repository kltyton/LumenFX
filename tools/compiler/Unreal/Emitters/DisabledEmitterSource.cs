using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

/** Init retains a disabled handle's instance and base properties without creating execution contexts. */
internal static class DisabledEmitterSource
{
    internal static JObject Compile(SourceAsset asset, JObject handle)
    {
        bool stateless = !EmitterHandleSource.IsStandard(handle);
        int reference = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
        SourceObject? emitter = reference == 0 ? null : asset.Object(reference);
        JObject? version = stateless || emitter is null ? null
            : EmitterVersionSource.Find(emitter, handle["VersionedInstance"]?.Value<string>("Version"));
        int analytic = handle.Value<int?>("StatelessEmitter") ?? 0;
        string sourceName = emitter is not null ? emitter.Properties.Value<string>("UniqueEmitterName") ?? ""
            : analytic == 0 ? "" : asset.Object(analytic).Properties.Value<string>("UniqueEmitterName") ?? "";
        return new JObject {
            ["name"] = (handle.Value<string>("Name") ?? throw new ConversionException("Disabled emitter has no handle name")).ToLowerInvariant(),
            ["source_name"] = sourceName, ["handle_id"] = handle.Value<string>("Id"),
            ["random_seed"] = version?.Value<int?>("RandomSeed") ?? 0,
            ["deterministic"] = version?.Value<bool?>("bDeterminism") ?? (version is null),
            ["local_space"] = version?.Value<bool?>("bLocalSpace") ?? (version is null),
            ["max_particles"] = 0, ["attributes"] = new JArray(), ["spawn_info"] = new JArray(),
            ["spawn_events"] = new JArray(), ["update_events"] = new JArray(), ["shared_event_ids"] = new JArray(),
            ["event_handlers"] = new JArray(), ["emitter_dependencies"] = new JArray(), ["renderers"] = new JArray(),
            ["bounds_mode"] = "Dynamic", ["fixed_bounds"] = JValue.CreateNull(),
            ["disabled"] = new JObject { ["stateless"] = stateless, ["has_emitter"] = !stateless && emitter is not null }
        };
    }
}
