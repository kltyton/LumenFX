using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Source-order system scalability rows and the owning EffectType policy. */
internal static class NiagaraSystemScalabilitySource
{
    private const int SignificanceHandlersVersion = 66;
    private const int VisibilityCullingVersion = 71;

    internal static JObject? Compile(SourceAsset asset, SourceObject system, AssetLibrary? library)
    {
        JObject properties = system.Properties;
        int reference = properties.Value<int?>("EffectType") ?? 0;
        if (reference == 0) return null;
        string path = asset.ObjectPath(reference);
        SourceAsset owner = reference > 0 ? asset : library?.Load(path)
            ?? throw new ConversionException($"Missing Niagara effect type package: {path}");
        SourceObject type = reference > 0 ? owner.Object(reference) : owner.ResolveObject(path, _ => true);
        JObject effectType = type.Properties;
        string frequency = Enum(effectType["UpdateFrequency"], 0, "SpawnOnly", "Low", "Medium", "High", "Continuous");
        int typeVersion = NiagaraImporter.NiagaraVersion(owner);
        int significance = effectType.Value<int?>("SignificanceHandler") ?? 0;
        string? handler = significance == 0 ? null : owner.ReferencedClassPath(significance);
        if (typeVersion < SignificanceHandlersVersion)
            handler = frequency == "SpawnOnly" ? null : "/Script/Niagara.NiagaraSignificanceHandlerDistance";
        bool overrides = properties.Value<bool?>("bOverrideScalabilitySettings") == true;
        bool allowLocal = overrides && properties.Value<bool?>("bOverrideAllowCullingForLocalPlayers") == true
            ? properties.Value<bool?>("bAllowCullingForLocalPlayersOverride") == true
            : effectType.Value<bool?>("bAllowCullingForLocalPlayers") == true;
        var authored = (effectType["SystemScalabilitySettings"]?["Settings"] as JArray ?? new()).Cast<JObject>();
        var systemRows = overrides
            ? (properties["SystemScalabilityOverrides"]?["Overrides"] as JArray ?? new()).Cast<JObject>()
            : Enumerable.Empty<JObject>();
        return new JObject {
            ["effect_type"] = path, ["allow_local_player_culling"] = allowLocal,
            ["significance_class"] = handler,
            ["update_frequency"] = frequency,
            ["cull_reaction"] = Enum(effectType["CullReaction"], 1, "Deactivate", "DeactivateImmediate", "DeactivateResume",
                "DeactivateImmediateResume", "PauseResume"),
            ["effect_settings"] = new JArray(authored.Select(row => Row(row, typeVersion, false))),
            ["overrides"] = new JArray(systemRows.Select(row => Row(row, NiagaraImporter.NiagaraVersion(asset), true)))
        };
    }

    private static JObject Row(JObject row, int version, bool isOverride)
    {
        JObject result = new() { ["platforms"] = NiagaraScalabilitySource.Platform(row["Platforms"]) };
        if (!isOverride || row.Value<bool?>("bOverrideDistanceSettings") == true)
            result["distance"] = Rule(row, "bCullByDistance", "MaxDistance");
        if (!isOverride || row.Value<bool?>("bOverrideInstanceCountSettings") == true)
            result["effect_instances"] = Rule(row, "bCullMaxInstanceCount", "MaxInstances");
        if (!isOverride || row.Value<bool?>("bOverridePerSystemInstanceCountSettings") == true)
            result["system_instances"] = Rule(row, "bCullPerSystemMaxInstanceCount", "MaxSystemInstances");
        if (!isOverride || row.Value<bool?>("bOverrideVisibilitySettings") == true) {
            JObject visibility = row["VisibilityCulling"] as JObject ?? new();
            bool legacy = version < VisibilityCullingVersion;
            result["visibility"] = new JObject {
                ["not_rendered"] = legacy ? row.Value<bool?>("bCullByMaxTimeWithoutRender_DEPRECATED") == true
                    : visibility.Value<bool?>("bCullWhenNotRendered") == true,
                ["max_time_without_render"] = legacy ? SourceAsset.Number(row["MaxTimeWithoutRender_DEPRECATED"])
                    : SourceAsset.Number(visibility["MaxTimeWithoutRender"], 1),
                ["outside_frustum"] = visibility.Value<bool?>("bCullByViewFrustum") == true,
                ["allow_frustum_precull"] = visibility.Value<bool?>("bAllowPreCullingByViewFrustum") == true,
                ["max_time_outside_frustum"] = SourceAsset.Number(visibility["MaxTimeOutsideViewFrustum"], 1)
            };
        }
        if (!isOverride || row.Value<bool?>("bOverrideGlobalBudgetScalingSettings") == true) {
            JObject budget = row["BudgetScaling"] as JObject ?? new();
            result["budget"] = new JObject {
                ["cull"] = budget.Value<bool?>("bCullByGlobalBudget") == true,
                ["max_usage"] = SourceAsset.Number(budget["MaxGlobalBudgetUsage"], 1),
                ["distance_scale"] = Ramp(budget, "bScaleMaxDistanceByGlobalBudgetUse", "MaxDistanceScaleByGlobalBudgetUse"),
                ["effect_instances_scale"] = Ramp(budget, "bScaleMaxInstanceCountByGlobalBudgetUse", "MaxInstanceCountScaleByGlobalBudgetUse"),
                ["system_instances_scale"] = Ramp(budget, "bScaleSystemInstanceCountByGlobalBudgetUse", "MaxSystemInstanceCountScaleByGlobalBudgetUse")
            };
        }
        if (!isOverride || row.Value<bool?>("bOverrideCullProxySettings") == true)
            result["proxy"] = new JObject {
                ["mode"] = Enum(row["CullProxyMode"], 0, "None", "Instanced_Rendered"),
                ["maximum"] = row.Value<int?>("MaxSystemProxies") ?? 32
            };
        return result;
    }

    private static JObject Rule(JObject row, string enabled, string limit) => new() {
        ["enabled"] = row.Value<bool?>(enabled) == true,
        ["limit"] = SourceAsset.Number(row[limit])
    };

    private static JToken Ramp(JObject row, string enabled, string field)
    {
        if (row.Value<bool?>(enabled) != true) return JValue.CreateNull();
        JObject value = row[field] as JObject ?? new();
        return new JArray(SourceAsset.Number(value["StartX"], .5), SourceAsset.Number(value["StartY"], 1),
            SourceAsset.Number(value["EndX"], 1), SourceAsset.Number(value["EndY"], .5));
    }

    private static string Enum(JToken? token, int fallback, params string[] names)
    {
        string? value = token?.Value<string>()?.Split("::").Last();
        if (value is null) return names[fallback];
        if (int.TryParse(value, out int index) && index >= 0 && index < names.Length) return names[index];
        if (names.Contains(value, StringComparer.Ordinal)) return value;
        throw new ConversionException($"Invalid Niagara scalability enum value: {value}");
    }
}
