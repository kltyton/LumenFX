using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Components.Sequence;

internal static class NiagaraLifeCycleTrackSource
{
    internal static JObject? Compile(AssetLibrary library, string path)
    {
        var asset = library.Load(path);
        var track = asset.ResolveObject(path, value => value.ClassName == "MovieSceneNiagaraSystemTrack");
        var sections = track.Properties["Sections"] as JArray ?? new JArray();
        var section = sections.Values<int>().Where(index => index > 0).Select(asset.Object)
            .FirstOrDefault(value => value.ClassName == "MovieSceneNiagaraSystemSpawnSection");
        if (section is null) return null;

        var movieScene = MovieSceneSource.OwningScene(asset, track).Properties;
        var source = section.Properties;
        var (start, end) = MovieSceneSource.SectionFrames(source["SectionRange"], asset.ObjectPath(section.Index));
        string age = MovieSceneSource.EnumValue(source["AgeUpdateMode"], "TickDeltaTime", "TickDeltaTime", "DesiredAge", "DesiredAgeNoSeek");
        return new JObject {
            ["source"] = asset.ObjectPath(track.Index),
            ["start_frame"] = start,
            ["end_frame"] = end,
            ["display_rate"] = MovieSceneSource.DisplayRate(movieScene["DisplayRate"]),
            ["frame_locked"] = MovieSceneSource.EnumValue(movieScene["EvaluationType"], "WithSubFrames", "FrameLocked", "WithSubFrames") == "FrameLocked",
            ["evaluate_behavior"] = MovieSceneSource.EnumValue(source["SectionEvaluateBehavior"], "ActivateIfInactive", "ActivateIfInactive", "None"),
            ["end_behavior"] = MovieSceneSource.EnumValue(source["SectionEndBehavior"], "SetSystemInactive", "SetSystemInactive", "Deactivate", "None"),
            ["age_update_mode"] = age switch {
                "TickDeltaTime" => "TICK_DELTA_TIME",
                "DesiredAge" => "DESIRED_AGE",
                "DesiredAgeNoSeek" => "DESIRED_AGE_NO_SEEK",
                _ => throw new ConversionException($"Unknown Niagara age update mode: {age}")
            },
            ["allow_scalability"] = source.Value<bool?>("bAllowScalability") ?? false
        };
    }
}
