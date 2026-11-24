using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

/** Platform predicates remain runtime data; effect-type settings and emitter overrides retain source order. */
internal static class NiagaraScalabilitySource
{
    internal static JObject Platform(JToken? source)
    {
        JObject value = source as JObject ?? new();
        return new JObject {
            ["quality_mask"] = value.Value<long?>("QualityLevelMask") ?? -1,
            ["profiles"] = new JArray((value["DeviceProfileStates"] as JArray ?? new()).OfType<JObject>().Select(profile => new JObject {
                ["name"] = profile.Value<string>("ProfileName") ?? "None",
                ["enabled_mask"] = profile.Value<long?>("QualityLevelMask") ?? 0,
                ["set_mask"] = profile.Value<long?>("SetQualityLevelMask") ?? 0 })),
            ["conditions"] = new JArray((value["CVarConditions"] as JArray ?? new()).OfType<JObject>().Select(condition => new JObject {
                ["console"] = condition.Value<string>("CVarName") ?? "None",
                ["pass"] = Response(condition["PassResponse"], "None"), ["fail"] = Response(condition["FailResponse"], "Disable"),
                ["bool_value"] = condition.Value<bool?>("Value") ?? true,
                ["min_int_enabled"] = condition.Value<bool?>("bUseMinInt") ?? true,
                ["min_int"] = condition.Value<int?>("MinInt") ?? 1,
                ["max_int_enabled"] = condition.Value<bool?>("bUseMaxInt") ?? false,
                ["max_int"] = condition.Value<int?>("MaxInt") ?? 1,
                ["min_float_enabled"] = condition.Value<bool?>("bUseMinFloat") ?? true,
                ["min_float"] = FloatWord(condition["MinFloat"], 1f),
                ["max_float_enabled"] = condition.Value<bool?>("bUseMaxFloat") ?? false,
                ["max_float"] = FloatWord(condition["MaxFloat"], 1f) }))
        };
    }

    internal static JObject Emitter(SourceAsset asset, SourceObject system, JObject emitter, AssetLibrary? library)
    {
        JArray effectSettings = new();
        int reference = system.Properties.Value<int?>("EffectType") ?? 0;
        if (reference != 0) {
            string path = asset.ObjectPath(reference);
            SourceAsset owner = reference > 0 ? asset : library?.Load(path)
                ?? throw new ConversionException($"A source library is required for Niagara effect type {path}");
            SourceObject effectType = reference > 0 ? owner.Object(reference) : owner.ResolveObject(path, _ => true);
            foreach (JObject settings in (effectType.Properties["EmitterScalabilitySettings"]?["Settings"] as JArray ?? new()).OfType<JObject>())
                effectSettings.Add(Setting(settings, false));
        }
        return new JObject {
            ["platforms"] = Platform(emitter["Platforms"]),
            ["effect_settings"] = effectSettings,
            ["overrides"] = new JArray((emitter["ScalabilityOverrides"]?["Overrides"] as JArray ?? new()).OfType<JObject>()
                .Select(value => Setting(value, true)))
        };
    }

    private static JObject Setting(JObject value, bool overrides) => new() {
        ["platforms"] = Platform(value["Platforms"]), ["scale_enabled"] = value.Value<bool?>("bScaleSpawnCount") ?? false,
        ["spawn_scale"] = FloatWord(value["SpawnCountScale"], 1f),
        ["override_spawn_scale"] = overrides && value.Value<bool?>("bOverrideSpawnCountScale") == true
    };
    private static uint FloatWord(JToken? value, float fallback) => unchecked((uint)BitConverter.SingleToInt32Bits((float)SourceAsset.Number(value, fallback)));
    private static string Response(JToken? value, string fallback)
    {
        string name = value?.Value<string>()?.Split("::").Last() ?? fallback;
        return name switch { "None" or "0" => "None", "Enable" or "1" => "Enable", "Disable" or "2" => "Disable",
            _ => throw new ConversionException($"Invalid Niagara CVar condition response {name}") };
    }
}
