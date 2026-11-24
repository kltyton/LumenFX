using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Scene;

/** Imports registered-source data for a saved water actor/component without executing its construction script. */
internal static class WaterBodySource
{
    private const string WaterActor = "/Script/Water.WaterBody";
    private static readonly (string Class, string Type)[] BodyTypes = [
        ("WaterBodyRiverComponent", "RIVER"), ("WaterBodyLakeComponent", "LAKE"),
        ("WaterBodyOceanComponent", "OCEAN"), ("WaterBodyCustomComponent", "TRANSITION")
    ];
    private static readonly string[] BuiltInActors = [
        "WaterBody", "WaterBodyRiver", "WaterBodyLake", "WaterBodyOcean", "WaterBodyCustom"
    ];

    internal static JObject? Compile(SourceAsset owner, string path, AssetLibrary? library)
    {
        library ??= owner.Library;
        if (library is null || ObjectPropertyFieldSource.Resolve(owner, path, library) is not { } source) return null;
        var (asset, value) = source;
        SourceObject actor, component;
        var type = TypeOf(asset, value, library);
        if (type is not null) {
            component = value;
            if (value.Outer <= 0) return null;
            actor = asset.Object(value.Outer);
            if (!IsWaterActor(asset, actor, library)) return null;
        } else {
            actor = value;
            if (!IsWaterActor(asset, actor, library)) return null;
            var candidates = asset.Objects.Where(item => asset.IsWithin(item, actor) && TypeOf(asset, item, library) is not null)
                .Take(2).ToArray();
            if (candidates.Length != 1) return null;
            component = candidates[0];
            type = TypeOf(asset, component, library)!;
        }
        var transform = ObjectPropertyFieldSource.CompileScene(asset, component, library);
        if (transform is null) return null;
        if (Property(asset, component, library, "WaterBodyExclusionVolumes")?.Value is JArray exclusions
                && exclusions.Any(token => ObjectPropertyReaderCompiler.ObjectPath(asset, token).Length != 0)) return null;
        if (Property(asset, component, library, "bUseBakedSimForQueriesAndPhysics")?.Value.Value<bool>() != false
                && ResolveProperty(asset, component, library, "BakedShallowWaterSim") is not null) return null;

        var metadata = ResolveProperty(asset, actor, library, "WaterSplineMetadata");
        var splineSource = Property(asset, actor, library, "SplineComp");
        JObject? spline = null;
        if (splineSource is { } splineField && splineField.Value.Value<int>() != 0) {
            spline = SplineComponentSource.CompileWaterSpline(splineField.Asset, splineField.Value.Value<int>(), library);
            if (spline is null) return null;
        }
        if (!TryWaves(asset, actor, library, out var waves)) return null;
        return new JObject {
            ["component"] = asset.ObjectPath(component.Index),
            ["type"] = type, ["transform"] = transform, ["spline"] = spline,
            ["has_spline_metadata"] = metadata is not null,
            ["depth"] = metadata is { } depth ? Curve(depth.Object.Properties["Depth"]) : null,
            ["speed"] = metadata is { } speed ? Curve(speed.Object.Properties["WaterVelocityScalar"]) : null,
            ["wave_mask_depth"] = Property(asset, component, library, "TargetWaveMaskDepth")?.Value.Value<float>() ?? 2048f,
            ["waves"] = waves
        };
    }

    private static bool IsWaterActor(SourceAsset asset, SourceObject value, AssetLibrary library)
        => BuiltInActors.Contains(value.ClassName, StringComparer.Ordinal)
            || ObjectPropertyFieldSource.IsOfClass(asset, value.Raw.Value<int>("ClassIndex"), WaterActor, library);

    private static string? TypeOf(SourceAsset asset, SourceObject value, AssetLibrary library)
    {
        foreach (var (native, type) in BodyTypes)
            if (value.ClassName == native || ObjectPropertyFieldSource.IsOfClass(asset, value.Raw.Value<int>("ClassIndex"),
                    "/Script/Water." + native, library)) return type;
        return null;
    }

    private static (SourceAsset Asset, JToken Value)? Property(SourceAsset asset, SourceObject source, AssetLibrary library, string name)
        => ObjectPropertyFieldSource.ComponentProperty(asset, source, library, name);

    private static (SourceAsset Asset, SourceObject Object)? ResolveProperty(SourceAsset asset, SourceObject source,
                                                                              AssetLibrary library, string name)
        => Property(asset, source, library, name) is { } property
            ? ObjectPropertyFieldSource.ResolveReference(property.Asset, property.Value.Value<int>(), library) : null;

    private static bool TryWaves(SourceAsset asset, SourceObject actor, AssetLibrary library, out JArray? waves)
    {
        waves = null;
        if (Property(asset, actor, library, "WaterWaves") is not { } waveRef || waveRef.Value.Value<int>() == 0) return true;
        var selected = ObjectPropertyFieldSource.ResolveReference(waveRef.Asset, waveRef.Value.Value<int>(), library);
        if (selected is not { } wave) return false;
        if (wave.Object.ClassName == "WaterWavesAssetReference") {
            var waterAsset = ResolveProperty(wave.Asset, wave.Object, library, "WaterWavesAsset");
            if (waterAsset is not { } target) return false;
            selected = ResolveProperty(target.Asset, target.Object, library, "WaterWaves");
        }
        if (selected is not { } gerstner || gerstner.Object.ClassName != "GerstnerWaterWaves"
                || gerstner.Object.Properties["GerstnerWaves"] is not JArray keys) return false;
        waves = new JArray(keys.OfType<JObject>().Select(key => new JObject {
            ["wavelength"] = key.Value<float?>("WaveLength") ?? 25,
            ["amplitude"] = key.Value<float?>("Amplitude") ?? 10,
            ["steepness"] = key.Value<float?>("Steepness") ?? 0,
            ["direction"] = Vector(key["Direction"], "XYZ", [1, 0, 0]),
            ["vector"] = key["WaveVector"] is { } vector ? Vector(vector, "XY", [0, 0]) : null,
            ["speed"] = key["WaveSpeed"], ["wka"] = key["WKA"], ["q"] = key["Q"]
        }));
        return true;
    }

    private static JObject? Curve(JToken? source)
    {
        if (source is not JObject curve || curve["Points"] is not JArray points || points.Count == 0) return null;
        var keys = new JArray();
        foreach (JObject key in points) {
            keys.Add(new JObject {
                ["time"] = SourceAsset.Number(key["InVal"]), ["value"] = SourceAsset.Number(key["OutVal"]),
                ["arrive"] = SourceAsset.Number(key["ArriveTangent"]), ["leave"] = SourceAsset.Number(key["LeaveTangent"]),
                ["interpolation"] = (key.Value<string>("InterpMode") ?? "CIM_Linear").Split("::").Last() switch {
                    "0" or "CIM_Linear" => "LINEAR", "2" or "CIM_Constant" => "CONSTANT",
                    "1" or "3" or "4" or "5" or "CIM_CurveAuto" or "CIM_CurveUser" or "CIM_CurveBreak" or "CIM_CurveAutoClamped" => "CUBIC",
                    var value => throw new ConversionException($"Unsupported Water spline metadata interpolation {value}")
                }
            });
        }
        float loop = curve.Value<bool?>("bIsLooped") == true ? curve.Value<float?>("LoopKeyOffset") ?? 0 : 0;
        if (loop > 0) {
            var last = keys.Last!.Value<float>("time");
            var first = (JObject)keys.First!.DeepClone();
            first["time"] = last + loop;
            keys.Add(first);
        }
        return new JObject { ["keys"] = keys };
    }

    private static JArray Vector(JToken? token, string axes, double[] defaults)
        => new(axes.Select((axis, index) => SourceAsset.Number((token as JObject)?[axis.ToString()], defaults[index])));
}
