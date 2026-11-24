using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Project settings used by UNiagaraSystem while caching its compiled data. */
internal sealed record NiagaraProjectSettings(float? MaximumDelta, bool LwcEnabled, string LwcMode, long LwcResetTiles,
                                             bool DefaultPixelCoverage)
{
    internal static readonly NiagaraProjectSettings Default = new(.125f, true, "REBASE", 4, true);

    internal static NiagaraProjectSettings Read(JToken? source)
    {
        if (source is null || source.Type == JTokenType.Null) return Default;
        if (source is not JObject value) throw new ConversionException("Niagara project settings must be an object");
        bool limitDelta = value.Value<bool?>("bLimitDeltaTime") ?? true;
        float delta = (float)SourceAsset.Number(value["MaxDeltaTimePerTick"], .125);
        if (limitDelta && (!float.IsFinite(delta) || delta < 0))
            throw new ConversionException("Invalid Niagara project MaxDeltaTimePerTick");
        long resetTiles = value.Value<long?>("LargeWorldCoordinateMaxTilesBeforeReset") ?? 4;
        if (resetTiles < 0 || resetTiles > uint.MaxValue)
            throw new ConversionException("Invalid Niagara LWC reset threshold");
        return new(limitDelta ? delta : null, value.Value<bool?>("bSystemsSupportLargeWorldCoordinates") ?? true,
            Mode(value["LargeWorldCoordinateTileUpdateMode"], "REBASE"), resetTiles, PixelCoverage(value));
    }

    private static bool PixelCoverage(JObject value) => value.Value<string>("DefaultPixelCoverageMode")?.Split("::").Last() switch
    {
        null or "Enabled" or "0" => true,
        "Disabled" or "1" => false,
        var mode => throw new ConversionException($"Invalid Niagara default pixel coverage mode {mode}")
    };

    internal JObject Lwc(JObject system) => new() {
        ["enabled"] = LwcEnabled && (system.Value<bool?>("bSupportLargeWorldCoordinates") ?? true),
        ["update_mode"] = Mode(system["LargeWorldCoordinateTileUpdateMode"], LwcMode),
        ["maximum_tiles_before_reset"] = LwcResetTiles
    };

    private static string Mode(JToken? value, string fallback)
    {
        if (value is JObject optional) {
            if (optional.Value<bool?>("IsSet") == false) return fallback;
            value = optional["Value"] ?? throw new ConversionException("Niagara LWC mode has no optional value");
        }
        if (value is null || value.Type == JTokenType.Null) return fallback;
        return value.Value<string>()?.Split("::").Last() switch {
            "0" or "ResetSimulation" => "RESET_SIMULATION",
            "1" or "Rebase" => "REBASE",
            "2" or "RebaseOrResetSimulation" => "REBASE_OR_RESET_SIMULATION",
            var mode => throw new ConversionException($"Invalid Niagara LWC tile update mode {mode}")
        };
    }
}
