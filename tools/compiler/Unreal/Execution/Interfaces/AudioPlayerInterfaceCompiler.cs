using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Audio;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class AudioPlayerInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceAudioPlayer";
    internal const string SettingsClass = "NiagaraDataInterfaceAudioPlayerSettings";

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "PlayAudioAtLocation" => inputs is 10 or 11 && outputs == 1,
            "PlayPersistentAudio" => inputs == 13 && outputs == 1,
            "UpdateAudioVolume" or "UpdateAudioPitch" or "SetPaused" => inputs == 2 && outputs == 0,
            "UpdateAudioLocation" or "UpdateAudioRotation" => inputs == 4 && outputs == 0,
            "SetBooleanParameter" or "SetIntegerParameter" or "SetFloatParameter" => inputs == 3 && outputs == 0,
            "SetInitialBooleanParameter" or "SetInitialIntegerParameter" or "SetInitialFloatParameter" => inputs == 4 && outputs == 0,
            _ => false
        };
        if (!valid) throw new ConversionException($"Invalid Niagara audio player signature: {name} ({inputs}, {outputs})");
    }

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var asset = source?.Asset ?? owner;
        var properties = source?.Object?.Properties;
        var data = new JObject {
            ["configuration"] = properties?["ConfigurationUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["parameter_names"] = properties?["ParameterNames"]?.DeepClone() ?? new JArray(),
            ["maximum_one_shots"] = properties?.Value<bool?>("bLimitPlaysPerTick") == false ? 0
                : properties?.Value<int?>("MaxPlaysPerTick") ?? 10,
            ["stop_on_destroy"] = properties?.Value<bool?>("bStopWhenComponentIsDestroyed") ?? true,
            ["looping_one_shots"] = properties?.Value<bool?>("bAllowLoopingOneShotSounds") ?? false,
            ["gameplay_only"] = properties?.Value<bool?>("bOnlyActiveDuringGameplay") ?? false
        };
        AddSource("SoundToPlay", "sound_source");
        AddSource("Attenuation", "attenuation_source");
        AddSource("Concurrency", "concurrency_source");
        return data;

        void AddSource(string field, string key) {
            string path = ObjectPropertyReaderCompiler.ObjectPath(asset, properties?[field]);
            if (!string.IsNullOrEmpty(path)) data[key] = path;
        }
    }

    internal static JObject Configuration(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = DataInterfaceInstanceSources.ResolveSource(owner, reference, SettingsClass, library);
        var properties = source.Object?.Properties ?? new JObject();
        bool attenuation = properties.Value<bool?>("bOverrideAttenuationSettings") ?? true;
        bool concurrency = properties.Value<bool?>("bOverrideConcurrency") ?? true;
        var data = new JObject { ["override_attenuation"] = attenuation, ["override_concurrency"] = concurrency };
        if (attenuation) data["attenuation"] = AudioAcousticsCompiler.Attenuation(source.Asset,
                properties["AttenuationSettings"] as JObject ?? new JObject(), library);
        string path = ObjectPropertyReaderCompiler.ObjectPath(source.Asset, properties["Concurrency"]);
        if (concurrency && path.Length != 0) data["concurrency"] = AudioAcousticsCompiler.ConcurrencyAsset(path,
                library ?? source.Asset.Library ?? throw new ConversionException($"Missing source audio concurrency {path}"));
        return data;
    }
}
