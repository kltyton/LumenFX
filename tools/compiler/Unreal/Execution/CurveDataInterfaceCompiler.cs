using Newtonsoft.Json.Linq;
using System.Globalization;

namespace LumenFx.Compiler.Unreal.Execution;

public static class CurveDataInterfaceCompiler
{
    internal static bool Supports(string type) => type is "NiagaraDataInterfaceCurve" or "NiagaraDataInterfaceVector2DCurve"
        or "NiagaraDataInterfaceVectorCurve" or "NiagaraDataInterfaceColorCurve" or "NiagaraDataInterfaceVector4Curve";

    internal static void CompileCpu(JObject binding, SourceAsset asset, SourceObject? data, string type,
        AssetLibrary? library, string? sourceReference = null)
    {
        int channels = Channels(type);
        string function = type switch {
            "NiagaraDataInterfaceCurve" => "SampleCurve", "NiagaraDataInterfaceVector2DCurve" => "SampleVector2DCurve",
            "NiagaraDataInterfaceVectorCurve" => "SampleVectorCurve", _ => "SampleColorCurve"
        };
        if (binding.Value<string>("name") != function || binding.Value<int>("inputs") != 1 || binding.Value<int>("outputs") != channels)
            throw new ConversionException($"Invalid curve function signature: {type}:{binding.Value<string>("name")}");
        binding["curves"] = CompileDefaults(asset, data, type, library, sourceReference);
    }

    internal static JArray CompileDefaults(SourceAsset asset, SourceObject? data, string type,
        AssetLibrary? library, string? sourceReference)
    {
        JObject properties = ResolveProperties(asset, data, type, library, sourceReference);
        return new JArray(Enumerable.Range(0, Channels(type)).Select(component =>
            Compile(properties, type, sourceReference ?? data?.Name ?? type, component)));
    }

    internal static void CompileGpu(JObject binding, SourceAsset asset, SourceObject? data, string type,
        AssetLibrary? library, string? sourceReference)
    {
        binding["properties"] = CompileGpuDefaults(asset, data, type, library, sourceReference);
        binding.Remove("source_reference");
    }

    internal static JObject CompileGpuDefaults(SourceAsset asset, SourceObject? data, string type,
        AssetLibrary? library, string? sourceReference)
    {
        JObject properties = (JObject)ResolveProperties(asset, data, type, library, sourceReference).DeepClone();
        if (properties["ShaderLUT"] is JArray)
        {
            // Tagged UE exports omit fields that match UNiagaraDataInterfaceCurveBase's defaults.
            properties["LUTMinTime"] ??= 0f;
            properties["LUTMaxTime"] ??= 1f;
            properties["LUTInvTimeRange"] ??= 1f;
            properties["LUTNumSamplesMinusOne"] ??= 0f;
        }
        return properties;
    }

    private static JObject ResolveProperties(SourceAsset asset, SourceObject? data, string type,
        AssetLibrary? library, string? sourceReference)
    {
        if (data is not null) return data.Properties;
        if (sourceReference?.EndsWith(".Default__" + type, StringComparison.Ordinal) == true)
            return new JObject {
                ["bUseLUT"] = true, ["LUTMinTime"] = 0f, ["LUTMaxTime"] = 1f, ["LUTInvTimeRange"] = 1f,
                ["LUTNumSamplesMinusOne"] = 0f, ["ShaderLUT"] = new JArray(Enumerable.Repeat(0f, Channels(type)))
            };
        if (sourceReference is null || library is null)
            throw new ConversionException($"Curve interface requires its source object: {asset.Name}:{sourceReference ?? type}");
        SourceAsset owner = library.Load(sourceReference);
        return owner.ResolveObject(sourceReference, value => value.ClassName == type).Properties;
    }

    private static int Channels(string type) => type switch {
        "NiagaraDataInterfaceCurve" => 1, "NiagaraDataInterfaceVector2DCurve" => 2,
        "NiagaraDataInterfaceVectorCurve" => 3, "NiagaraDataInterfaceColorCurve" or "NiagaraDataInterfaceVector4Curve" => 4,
        _ => throw new ConversionException($"Unsupported curve interface: {type}")
    };

    public static JObject Compile(SourceObject data, int component)
        => Compile(data.Properties, data.ClassName, data.Name, component);

    private static JObject Compile(JObject properties, string type, string name, int component)
    {
        int channels = Channels(type);
        if (component < 0 || component >= channels) throw new ConversionException($"Invalid curve component: {name}");
        if (properties.Value<bool?>("bUseLUT") != false)
        {
            var source = properties["ShaderLUT"] as JArray ?? throw new ConversionException($"Missing baked curve table: {name}");
            int count = checked((int)SourceAsset.Number(properties["LUTNumSamplesMinusOne"]) + 1);
            if (count < 1 || source.Count != count * channels)
                throw new ConversionException($"Baked curve size does not match its layout: {name}");
            return new JObject { ["lookup"] = new JObject {
                ["minimum_time"] = SourceAsset.Number(properties["LUTMinTime"]),
                ["inverse_time_range"] = InverseTimeRange(properties["LUTInvTimeRange"], count),
                ["values"] = new JArray(Enumerable.Range(0, count).Select(i => source[i * channels + component]!.DeepClone())) } };
        }
        string field = type switch {
            "NiagaraDataInterfaceCurve" => "Curve",
            "NiagaraDataInterfaceColorCurve" => new[] { "RedCurve", "GreenCurve", "BlueCurve", "AlphaCurve" }[component],
            _ => new[] { "XCurve", "YCurve", "ZCurve", "WCurve" }[component] };
        JObject curve = properties[field] as JObject ?? throw new ConversionException($"Missing curve {field}: {name}");
        return CompileRichCurve(curve);
    }

    internal static JObject CompileRichCurve(JObject curve)
    {
        JArray keys = new();
        foreach (JObject key in curve["Keys"] as JArray ?? new JArray())
        {
            keys.Add(new JObject { ["time"] = SourceAsset.Number(key["Time"]), ["value"] = SourceAsset.Number(key["Value"]),
                ["arrive"] = SourceAsset.Number(key["ArriveTangent"]), ["leave"] = SourceAsset.Number(key["LeaveTangent"]),
                ["arrive_weight"] = SourceAsset.Number(key["ArriveTangentWeight"]),
                ["leave_weight"] = SourceAsset.Number(key["LeaveTangentWeight"]),
                ["weight_mode"] = (key.Value<string>("TangentWeightMode") ?? "RCTWM_WeightedNone").Split("::").Last(),
                ["interpolation"] = (key.Value<string>("InterpMode") ?? "RCIM_Linear").Split("::").Last() switch {
                    "RCIM_Constant" => "constant", "RCIM_Linear" => "linear", "RCIM_Cubic" or "RCIM_None" => "cubic",
                    var mode => throw new ConversionException($"Unsupported curve interpolation {mode}") } });
        }
        if (keys.Count == 0)
        {
            double defaultValue = SourceAsset.Number(curve["DefaultValue"]);
            keys.Add(new JObject { ["time"] = 0, ["value"] = defaultValue >= float.MaxValue ? 0 : defaultValue });
        }
        return new JObject { ["keys"] = keys,
            ["pre_extrapolation"] = (curve.Value<string>("PreInfinityExtrap") ?? "RCCE_Constant").Split("::").Last(),
            ["post_extrapolation"] = (curve.Value<string>("PostInfinityExtrap") ?? "RCCE_Constant").Split("::").Last() };
    }

    private static double InverseTimeRange(JToken? value, int samples)
    {
        if (samples == 1 && value is not null && value.Type != JTokenType.Null) {
            double number = value.Type == JTokenType.String
                ? double.Parse(value.Value<string>()!, CultureInfo.InvariantCulture) : value.Value<double>();
            if (double.IsPositiveInfinity(number)) return number;
        }
        return SourceAsset.Number(value, 1);
    }
}
