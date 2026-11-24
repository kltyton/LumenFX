using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class AudioAnalysisInterfaceCompiler
{
    internal const string Spectrum = "NiagaraDataInterfaceAudioSpectrum";
    internal const string Oscilloscope = "NiagaraDataInterfaceAudioOscilloscope";

    internal static JObject Definition(SourceAsset owner, int reference, string type, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, type, library);
        var properties = source?.Object?.Properties;
        return new JObject {
            ["spectrum"] = type == Spectrum,
            ["submix"] = ObjectPropertyReaderCompiler.ObjectPath(source?.Asset ?? owner, properties?["Submix"]),
            ["resolution"] = properties?.Value<int?>("Resolution") ?? 512,
            ["scope_milliseconds"] = properties?.Value<float?>("ScopeInMilliseconds") ?? 20f,
            ["minimum_frequency"] = properties?.Value<float?>("MinimumFrequency") ?? 55f,
            ["maximum_frequency"] = properties?.Value<float?>("MaximumFrequency") ?? 10000f,
            ["noise_floor"] = properties?.Value<float?>("NoiseFloorDb") ?? -60f
        };
    }

    internal static void ValidateCpu(string type, string name, int inputs, int outputs)
    {
        bool valid = outputs == 1 && ((type, name) switch {
            (Spectrum, "AudioSpectrum") or (Oscilloscope, "SampleAudioBuffer") => inputs == 2,
            (Spectrum, "GetNumChannels") or (Oscilloscope, "GetAudioBufferNumChannels") => inputs == 0,
            _ => false
        });
        if (!valid) throw new ConversionException($"Invalid Niagara audio analyzer signature: {name} ({inputs}, {outputs})");
    }
}
