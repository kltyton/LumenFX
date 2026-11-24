using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Components.Sequence;

internal static class NiagaraCacheTrackSource
{
    internal static JObject Compile(AssetLibrary library, string path)
    {
        var asset = library.Load(path);
        var track = asset.ResolveObject(path, value => value.ClassName == "MovieSceneNiagaraCacheTrack");
        JArray sections = new();
        foreach (int index in (track.Properties["AnimationSections"] as JArray ?? new JArray()).Values<int>()) {
            if (index <= 0) continue;
            var section = asset.Object(index);
            if (section.ClassName != "MovieSceneNiagaraCacheSection") continue;
            var properties = section.Properties;
            if (properties.Value<bool?>("bIsActive") == false || properties["Params"] is not JObject parameters) continue;
            int cacheIndex = parameters.Value<int?>("SimCache") ?? 0;
            if (cacheIndex == 0) continue;
            var (start, end) = MovieSceneSource.SectionFrames(properties["SectionRange"], asset.ObjectPath(section.Index));
            sections.Add(new JObject {
                ["start_frame"] = start,
                ["end_frame"] = end,
                ["sim_cache_asset"] = new JObject {
                    ["asset_source"] = MovieSceneSource.NativeObjectPath(asset.ObjectPath(cacheIndex)),
                    ["allow_invalid_cache"] = true
                },
                ["play_rate"] = SourceAsset.Number(parameters["PlayRate"], 1),
                ["start_offset"] = MovieSceneSource.FrameNumber(parameters["StartFrameOffset"]) ?? 0,
                ["end_offset"] = MovieSceneSource.FrameNumber(parameters["EndFrameOffset"]) ?? 0,
                ["first_loop_offset"] = MovieSceneSource.FrameNumber(parameters["FirstLoopStartFrameOffset"]) ?? 0,
                ["reverse"] = parameters.Value<bool?>("bReverse") ?? false,
                ["replay_mode"] = MovieSceneSource.EnumValue(parameters["CacheReplayPlayMode"], "DisplayCacheOnly",
                    "SimWithoutCache", "DisplayCacheOnly"),
                ["stretch_mode"] = MovieSceneSource.EnumValue(parameters["SectionStretchMode"], "TimeDilate", "Repeat", "TimeDilate")
            });
        }
        return new JObject { ["source"] = asset.ObjectPath(track.Index), ["sections"] = sections };
    }
}
