using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Audio;

internal static class AudioAcousticsCompiler
{
    internal static JObject AttenuationAsset(string path, AssetLibrary library)
    {
        var owner = library.Load(path);
        var source = owner.ResolveObject(path, value => value.ClassName == "SoundAttenuation");
        return Attenuation(owner, source.Properties["Attenuation"] as JObject ?? new JObject(), library);
    }

    internal static JObject Attenuation(SourceAsset owner, JObject source, AssetLibrary? library)
    {
        var extents = source["AttenuationShapeExtents"] as JObject;
        var result = new JObject {
            ["attenuate"] = source.Value<bool?>("bAttenuate") ?? true,
            ["spatialize"] = source.Value<bool?>("bSpatialize") ?? true,
            ["shape"] = EnumValue(source["AttenuationShape"], ["Sphere", "Capsule", "Box", "Cone"], 0),
            ["model"] = EnumValue(source["DistanceAlgorithm"], ["Linear", "Logarithmic", "Inverse", "LogReverse", "NaturalSound", "Custom"], 0),
            ["falloff_mode"] = EnumValue(source["FalloffMode"], ["Continues", "Silent", "Hold"], 0),
            ["decibels"] = source.Value<float?>("dBAttenuationAtMax") ?? -60f,
            ["extents"] = new JArray(extents?.GetValue("X", StringComparison.OrdinalIgnoreCase)?.Value<float>() ?? 400f,
                extents?.GetValue("Y", StringComparison.OrdinalIgnoreCase)?.Value<float>() ?? 0f,
                extents?.GetValue("Z", StringComparison.OrdinalIgnoreCase)?.Value<float>() ?? 0f),
            ["cone_offset"] = source.Value<float?>("ConeOffset") ?? 0f,
            ["falloff"] = source.Value<float?>("FalloffDistance") ?? 3600f,
            ["cone_sphere_radius"] = source.Value<float?>("ConeSphereRadius") ?? 0f,
            ["cone_sphere_falloff"] = source.Value<float?>("ConeSphereFalloffDistance") ?? 0f
        };
        if (result.Value<string>("model") == "Custom")
            result["curve"] = CurveDataInterfaceCompiler.CompileRichCurve(
                RuntimeFloatCurve(owner, source["CustomAttenuationCurve"] as JObject, library));
        return result;
    }

    internal static JObject RuntimeFloatCurve(SourceAsset owner, JObject? source, AssetLibrary? library)
    {
        if (source?.Value<int?>("ExternalCurve") is not int reference || reference == 0)
            return source?["EditorCurveData"] as JObject ?? source ?? new JObject();
        string path = owner.ObjectPath(reference);
        var target = reference > 0 ? owner.Object(reference)
            : ((library ?? owner.Library)?.Load(path) ?? throw new ConversionException($"Missing source audio curve {path}"))
                .ResolveObject(path, value => value.ClassName == "CurveFloat");
        return target.Properties["FloatCurve"] as JObject ?? throw new ConversionException($"Missing rich audio curve {path}");
    }

    internal static JObject ConcurrencyAsset(string path, AssetLibrary library)
    {
        var owner = library.Load(path);
        var source = owner.ResolveObject(path, value => value.ClassName == "SoundConcurrency");
        var settings = source.Properties["Concurrency"] as JObject ?? new JObject();
        return Concurrency(path, settings);
    }

    internal static JArray ConcurrencySet(SourceAsset asset, SourceObject sound, AssetLibrary library)
    {
        if (sound.Properties.Value<bool?>("bOverrideConcurrency") == true)
            return new JArray(Concurrency(asset.ObjectPath(sound.Index), sound.Properties["ConcurrencyOverrides"] as JObject ?? new JObject()));
        return new JArray((sound.Properties["ConcurrencySet"] as JArray ?? new JArray())
            .Select(value => ObjectPropertyReaderCompiler.ObjectPath(asset, value)).Where(path => path.Length != 0)
            .Distinct(SourceNameOrder.Instance).Select(path => ConcurrencyAsset(path, library)));
    }

    private static JObject Concurrency(string path, JObject settings)
    {
        int maximum = settings.Value<int?>("MaxCount") ?? 16;
        if (settings.Value<bool?>("bEnableMaxCountPlatformScaling") == true) {
            var scaling = settings["PlatformMaxCount"] as JObject;
            maximum = scaling?["PerPlatform"]?.Value<int?>("Windows") ?? scaling?.Value<int?>("Default") ?? maximum;
        }
        return new JObject {
            ["path"] = path,
            ["maximum"] = maximum > 0 ? maximum : 16,
            ["per_owner"] = settings.Value<bool?>("bLimitToOwner") ?? false,
            ["rule"] = EnumValue(settings["ResolutionRule"], ["PreventNew", "StopOldest", "StopFarthestThenPreventNew",
                "StopFarthestThenOldest", "StopLowestPriority", "StopQuietest", "StopLowestPriorityThenPreventNew"], 3),
            ["retrigger"] = settings.Value<float?>("RetriggerTime") ?? 0f,
            ["release"] = settings.Value<float?>("VoiceStealReleaseTime") ?? 0f,
            ["duck_scale"] = settings.Value<float?>("VolumeScale") ?? 1f,
            ["duck_mode"] = EnumValue(settings["VolumeScaleMode"], ["Default", "Distance", "Priority"], 0),
            ["duck_attack"] = settings.Value<float?>("VolumeScaleAttackTime") ?? .01f,
            ["duck_release"] = settings.Value<float?>("VolumeScaleReleaseTime") ?? .5f,
            ["duck_recover"] = settings.Value<bool?>("bVolumeScaleCanRelease") ?? false
        };
    }

    private static string EnumValue(JToken? value, string[] names, int fallback) => value switch {
        null => names[fallback],
        { Type: JTokenType.Integer } ordinal when ordinal.Value<int>() >= 0 && ordinal.Value<int>() < names.Length => names[ordinal.Value<int>()],
        { Type: JTokenType.String } name when names.Contains(name.Value<string>()?.Split("::").Last(), StringComparer.Ordinal) =>
            name.Value<string>()!.Split("::").Last(),
        _ => throw new ConversionException($"Unknown Unreal audio setting: {value}")
    };
}
