using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Localization;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Audio;

internal static class SoundCueCompiler
{
    internal sealed record WaveAsset(string Id, NativeAudioWave.Wave Data, string? Submix, JObject? SubmixGraph)
    {
        internal JObject Describe(bool looping)
        {
            var description = new JObject {
                ["sound"] = Id, ["duration"] = Data.Duration, ["volume"] = Data.Volume, ["pitch"] = Data.Pitch,
                ["looping"] = looping
            };
            if (Submix is not null) description["submix"] = Submix;
            if (SubmixGraph is not null) description["submix_graph"] = SubmixGraph.DeepClone();
            return description;
        }
    }

    internal static async Task<JObject> CompileAsync(SourceAsset asset, SourceObject cue, Func<string, Task<WaveAsset>> waveLoader,
        NativeSoundRouting routing)
    {
        var stack = new HashSet<int>();
        var root = await Node(cue.Properties.Value<int>("FirstNode")).ConfigureAwait(false);
        return new JObject {
            ["volume"] = cue.Properties.Value<float?>("VolumeMultiplier") ?? .75f,
            ["pitch"] = cue.Properties.Value<float?>("PitchMultiplier") ?? 1f,
            ["node"] = root
        };

        async Task<JObject> Node(int reference, bool needsMaturity = false)
        {
            if (reference == 0) return new JObject { ["kind"] = "empty" };
            if (reference < 0 || !stack.Add(reference))
                throw new ConversionException($"Invalid recursive sound cue node: {asset.ObjectPath(reference)}");
            try {
                var node = asset.Object(reference);
                var source = node.Properties;
                var result = new JObject { ["kind"] = node.ClassName, ["source_index"] = reference };
                switch (node.ClassName) {
                    case "SoundNodeWavePlayer":
                        string path = ObjectPropertyReaderCompiler.ObjectPath(asset, source["SoundWaveAssetPtr"] ?? source["SoundWave"]);
                        if (path.Length == 0) return new JObject { ["kind"] = "empty" };
                        var wave = await waveLoader(path).ConfigureAwait(false);
                        result.Merge(wave.Describe(source.Value<bool?>("bLooping") ?? false));
                        if (wave.Data.Mature is bool mature) result["mature"] = mature;
                        else if (needsMaturity)
                            throw new ConversionException($"Sound Node Mature requires native SoundWave maturity metadata: {path}");
                        return result;
                    case "SoundNodeMature":
                        needsMaturity = true;
                        break;
                    case "SoundNodeWaveParam":
                        result["parameter"] = source.Value<string>("WaveParameterName") ?? "";
                        break;
                    case "SoundNodeDialoguePlayer":
                        var parameter = source["DialogueWaveParameter"] as JObject;
                        string dialogue = ObjectPropertyReaderCompiler.ObjectPath(asset, parameter?["DialogueWave"]);
                        var context = parameter?["Context"] as JObject;
                        string speaker = ObjectPropertyReaderCompiler.ObjectPath(asset, context?["Speaker"]);
                        if (dialogue.Length == 0 || speaker.Length == 0) return new JObject { ["kind"] = "empty" };
                        string[] targets = context?["Targets"] is JArray targetList
                            ? targetList.Select(target => ObjectPropertyReaderCompiler.ObjectPath(asset, target)).ToArray() : [""];
                        var localization = asset.Library?.Localization ?? throw new ConversionException(
                            $"Unreal DialogueWave requires --text-native-tools: {dialogue}");
                        var mapping = await localization.DialogueAsync(dialogue, speaker, targets).ConfigureAwait(false);
                        if (mapping.Default.Wave is null && mapping.Fallback.Wave is null
                            && mapping.Languages.Values.All(line => line.Wave is null)) return new JObject { ["kind"] = "empty" };
                        bool loopRecording = source.Value<bool?>("bLooping") == true;
                        result.Merge(await DescribeDialogue(mapping.Default, loopRecording).ConfigureAwait(false));
                        if (mapping.Languages.Count != 0 || mapping.Default != mapping.Fallback) {
                            var lines = new JObject();
                            foreach (var language in mapping.Languages)
                                lines[language.Key] = await DescribeDialogue(language.Value, loopRecording).ConfigureAwait(false);
                            result["localized_dialogue"] = lines;
                            result["dialogue_fallback"] = await DescribeDialogue(mapping.Fallback, loopRecording).ConfigureAwait(false);
                        }
                        return result;
                    case "SoundNodeMixer":
                    case "SoundNodeConcatenator":
                        result["gains"] = source["InputVolume"]?.DeepClone() ?? new JArray();
                        break;
                    case "SoundNodeRandom":
                        result["weights"] = source["Weights"]?.DeepClone() ?? new JArray();
                        result["without_replacement"] = source.Value<bool?>("bRandomizeWithoutReplacement") ?? true;
                        result["preselect"] = source.Value<int?>("PreselectAtLevelLoad") ?? 0;
                        result["exclude_branch_culling"] = source.Value<bool?>("bShouldExcludeFromBranchCulling") == true
                            || source.Value<bool?>("bSoundCueExcludedFromBranchCulling") == true
                            || cue.Properties.Value<bool?>("bExcludeFromRandomNodeBranchCulling") == true;
                        break;
                    case "SoundNodeModulator":
                        result["volume"] = Range(source, "VolumeMin", "VolumeMax", .95f, 1.05f);
                        result["pitch"] = Range(source, "PitchMin", "PitchMax", .95f, 1.05f);
                        break;
                    case "SoundNodeModulatorContinuous":
                        result["volume_parameter"] = Modulation(source["VolumeModulationParams"] as JObject);
                        result["pitch_parameter"] = Modulation(source["PitchModulationParams"] as JObject);
                        break;
                    case "SoundNodeOscillator":
                        result["amplitude"] = Range(source, "AmplitudeMin", "AmplitudeMax", 0, 0);
                        result["frequency"] = Range(source, "FrequencyMin", "FrequencyMax", 0, 0);
                        result["offset"] = Range(source, "OffsetMin", "OffsetMax", 0, 0);
                        result["center"] = Range(source, "CenterMin", "CenterMax", 0, 0);
                        result["modulate_volume"] = source.Value<bool?>("bModulateVolume") ?? false;
                        result["modulate_pitch"] = source.Value<bool?>("bModulatePitch") ?? false;
                        break;
                    case "SoundNodeDoppler":
                        result["intensity"] = source.Value<float?>("DopplerIntensity") ?? 1f;
                        result["smooth"] = source.Value<bool?>("bUseSmoothing") ?? false;
                        result["speed"] = source.Value<float?>("SmoothingInterpSpeed") ?? 5f;
                        break;
                    case "SoundNodeEnveloper":
                        result["volume"] = Range(source, "VolumeMin", "VolumeMax", 1f, 1f);
                        result["pitch"] = Range(source, "PitchMin", "PitchMax", 1f, 1f);
                        result["volume_curve"] = EnvelopeCurve(asset, source, "Volume");
                        result["pitch_curve"] = EnvelopeCurve(asset, source, "Pitch");
                        result["loop"] = source.Value<bool?>("bLoop") ?? false;
                        result["loop_indefinitely"] = source.Value<bool?>("bLoopIndefinitely") ?? false;
                        result["loop_start"] = source.Value<float?>("LoopStart") ?? 0f;
                        result["loop_end"] = source.Value<float?>("LoopEnd") ?? 0f;
                        result["loop_count"] = source.Value<int?>("LoopCount") ?? 0;
                        result["after_loop"] = source.Value<float?>("DurationAfterLoop") ?? 0f;
                        break;
                    case "SoundNodeDelay":
                        result["delay"] = Range(source, "DelayMin", "DelayMax", 0, 0);
                        break;
                    case "SoundNodeLooping":
                        result["loops"] = source.Value<bool?>("bLoopIndefinitely") == false ? source.Value<int?>("LoopCount") ?? 1 : -1;
                        break;
                    case "SoundNodeGroupControl":
                        result["limits"] = source["GroupSizes"]?.DeepClone() ?? new JArray();
                        break;
                    case "SoundNodeSoundClass":
                        string classPath = ObjectPropertyReaderCompiler.ObjectPath(asset, source["SoundClassOverride"]);
                        if (classPath.Length != 0)
                            result["sound_class"] = (await routing.SoundClassAsync(classPath).ConfigureAwait(false)).DeepClone();
                        break;
                    case "SoundNodeBranch":
                        result["parameter"] = source.Value<string>("BoolParameterName") ?? "";
                        break;
                    case "SoundNodeSwitch":
                        result["parameter"] = source.Value<string>("IntParameterName") ?? "";
                        break;
                    case "SoundNodeQualityLevel":
                        result["cooked_level"] = source.Value<int?>("CookedQualityLevelIndex") ?? -1;
                        break;
                    case "SoundNodeAttenuation":
                        if (source.Value<bool?>("bOverrideAttenuation") == true)
                            result["attenuation"] = AudioAcousticsCompiler.Attenuation(asset,
                                source["AttenuationOverrides"] as JObject ?? new JObject(), asset.Library);
                        else {
                            string attenuation = ObjectPropertyReaderCompiler.ObjectPath(asset, source["AttenuationSettings"]);
                            if (attenuation.Length != 0) result["attenuation"] = AudioAcousticsCompiler.AttenuationAsset(attenuation,
                                asset.Library ?? throw new ConversionException($"Missing source cue attenuation: {attenuation}"));
                        }
                        break;
                    case "SoundNodeDistanceCrossFade":
                    case "SoundNodeParamCrossFade":
                        result["parameter"] = node.ClassName == "SoundNodeParamCrossFade" ? source.Value<string>("ParamName") ?? "" : null;
                        result["edges"] = new JArray((source["CrossFadeInput"] as JArray ?? new()).OfType<JObject>().Select(edge => new JObject {
                            ["in_start"] = edge.Value<float?>("FadeInDistanceStart") ?? 0,
                            ["in_end"] = edge.Value<float?>("FadeInDistanceEnd") ?? 0,
                            ["out_start"] = edge.Value<float?>("FadeOutDistanceStart") ?? 0,
                            ["out_end"] = edge.Value<float?>("FadeOutDistanceEnd") ?? 0,
                            ["volume"] = edge.Value<float?>("Volume") ?? 1
                        }));
                        break;
                    default:
                        throw new ConversionException($"Unsupported Niagara Sound Cue node {node.ClassName}: {asset.ObjectPath(reference)}");
                }
                var children = new JArray();
                foreach (int child in (source["ChildNodes"] as JArray ?? new()).Values<int>())
                    children.Add(await Node(child, needsMaturity).ConfigureAwait(false));
                result["children"] = children;
                return result;
            } finally { stack.Remove(reference); }
        }

        async Task<JObject> DescribeDialogue(NativeLocalization.DialogueLine line, bool loopRecording)
        {
            JObject description;
            if (line.Wave is { Length: > 0 } path) {
                var recording = await waveLoader(path).ConfigureAwait(false);
                description = recording.Describe(loopRecording || recording.Data.Looping);
            } else description = new JObject();
            description["subtitle"] = line.Subtitle;
            return description;
        }
    }

    private static JArray Range(JObject source, string min, string max, float defaultMin, float defaultMax) =>
        new(source.Value<float?>(min) ?? defaultMin, source.Value<float?>(max) ?? defaultMax);

    private static JObject EnvelopeCurve(SourceAsset asset, JObject source, string channel)
    {
        JObject? modern = source[channel + "Curve"] as JObject;
        string legacy = ObjectPropertyReaderCompiler.ObjectPath(asset, source[channel + "InterpCurve_DEPRECATED"]
            ?? source[channel + "InterpCurve"]);
        if (modern is not null || legacy.Length == 0)
            return CurveDataInterfaceCompiler.CompileRichCurve(AudioAcousticsCompiler.RuntimeFloatCurve(asset, modern, asset.Library));
        SourceAsset owner = legacy.StartsWith(asset.Name + ".", StringComparison.OrdinalIgnoreCase) ? asset
            : (asset.Library?.Load(legacy) ?? throw new ConversionException($"Missing legacy envelope curve {legacy}"));
        var distribution = owner.ResolveObject(legacy, value => value.ClassName == "DistributionFloatConstantCurve");
        var points = distribution.Properties["ConstantCurve"]?["Points"] as JArray
            ?? throw new ConversionException($"Missing legacy envelope points {legacy}");
        var curve = new JObject { ["Keys"] = new JArray(points.OfType<JObject>().Select(point => new JObject {
            ["Time"] = SourceAsset.Number(point["InVal"]), ["Value"] = SourceAsset.Number(point["OutVal"])
        }).OrderBy(point => point.Value<double>("Time"))) };
        return CurveDataInterfaceCompiler.CompileRichCurve(curve);
    }

    private static JObject Modulation(JObject? source)
    {
        int mode = source?["ParamMode"] switch {
            null => 0,
            { Type: JTokenType.Integer } number when number.Value<int>() is >= 0 and <= 2 => number.Value<int>(),
            { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "MPM_Normal" => 0,
            { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "MPM_Abs" => 1,
            { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "MPM_Direct" => 2,
            _ => throw new ConversionException("Unknown Sound Cue modulation mode")
        };
        return new JObject {
            ["parameter"] = source?.Value<string>("ParameterName") ?? "",
            ["default"] = source?.Value<float?>("Default") ?? 1f,
            ["input_min"] = source?.Value<float?>("MinInput") ?? 0f,
            ["input_max"] = source?.Value<float?>("MaxInput") ?? 1f,
            ["output_min"] = source?.Value<float?>("MinOutput") ?? 0f,
            ["output_max"] = source?.Value<float?>("MaxOutput") ?? 1f,
            ["mode"] = mode
        };
    }
}
