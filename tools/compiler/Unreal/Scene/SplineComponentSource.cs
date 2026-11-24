using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Scene;

/** Reads serialized SplineComponent data in both the fixed engine's legacy and native spline modes. */
internal static class SplineComponentSource
{
    private const string SplineType = "/Script/Engine.SplineComponent";
    private const string WaterSplineType = "/Script/Water.WaterSplineComponent";
    private const string ActorType = "/Script/Engine.Actor";

    internal static JObject? Compile(SourceAsset owner, string path, AssetLibrary? library)
        => ObjectPropertyFieldSource.Resolve(owner, path, library) is { } resolved
            ? Compile(resolved.Asset, resolved.Object, library) : null;

    internal static JObject? Compile(SourceAsset owner, int reference, AssetLibrary? library)
        => ObjectPropertyFieldSource.ResolveReference(owner, reference, library) is { } resolved
            ? Compile(resolved.Asset, resolved.Object, library) : null;

    internal static JObject? CompileWaterSpline(SourceAsset owner, int reference, AssetLibrary? library)
        => ObjectPropertyFieldSource.ResolveReference(owner, reference, library) is { } resolved
            ? Compile(resolved.Asset, resolved.Object, library, WaterSplineType) : null;

    private static JObject? Compile(SourceAsset asset, SourceObject source, AssetLibrary? library, string componentType = SplineType)
    {
        library ??= asset.Library;
        if (library is null) return null;
        bool HasType(SourceObject value, string type) => ObjectPropertyFieldSource.IsOfClass(asset,
            value.Raw.Value<int>("ClassIndex"), type, library);
        SourceObject? component = source;
        if (!HasType(source, componentType)) {
            if (!HasType(source, ActorType)) return null;
            var candidates = asset.Objects.Where(value => asset.IsWithin(value, source) && HasType(value, componentType)).Take(2).ToArray();
            if (candidates.Length > 1) return null; // Native FindComponentByClass uses live actor component order.
            component = candidates.FirstOrDefault();
        }
        if (component is null)
            return SourceNameOrder.Equal(asset.ObjectPath(source.Raw.Value<int>("ClassIndex")), ActorType)
                ? new JObject { ["component"] = null } : null;
        if (ObjectPropertyFieldSource.CompileScene(asset, component, library) is not { } transform) return null;
        JToken? Property(string field) => ObjectPropertyFieldSource.ComponentProperty(asset, component, library, field)?.Value is
            { Type: not JTokenType.Null } value ? value : null;
        if (Property("bModifiedByConstructionScript")?.Value<bool>() == true
            && Property("bSplineHasBeenEdited")?.Value<bool>() != true) return null;

        var authorityProperty = Property("LastAuthority");
        string authority = (authorityProperty?.Type == JTokenType.String
            ? authorityProperty.Value<string>()! : authorityProperty?.ToString() ?? "Unset").Split("::").Last();
        var curves = Property("SplineCurves") as JObject;
        var nativeSpline = ObjectPropertyFieldSource.ComponentProperty(asset, component, library, "Spline");
        JObject native = NativeSplineComponentSource.Compile(nativeSpline?.Asset ?? asset, nativeSpline?.Value, authority);
        var position = curves?["Position"] as JObject;
        var rotation = curves?["Rotation"] as JObject;
        var scale = curves?["Scale"] as JObject;
        bool closed = position?.Value<bool?>("bIsLooped") ?? Property("bClosedLoop")?.Value<bool>() ?? false;
        JArray positions, rotations, scales;
        if (curves is null && component.Raw["Data"] is JArray) {
            // USplineComponent::SetDefaultSpline creates two CurveAuto keys before UpdateSpline.
            double tangent = closed || Property("bStationaryEndpoints")?.Value<bool>() == true ? 0 : 100;
            positions = DefaultKeys([0, 0, 0], [100, 0, 0], [tangent, 0, 0]);
            rotations = DefaultKeys([0, 0, 0, 1], [0, 0, 0, 1], [0, 0, 0, 1]);
            scales = DefaultKeys([1, 1, 1], [1, 1, 1], [0, 0, 0]);
        } else {
            if (position is null || rotation is null || scale is null) return null;
            positions = Keys(position, "XYZ", false);
            rotations = Keys(rotation, "XYZW", true);
            scales = Keys(scale, "XYZ", false);
        }
        if (positions.Count != rotations.Count || positions.Count != scales.Count)
            throw new ConversionException($"Spline curve channels have different point counts: {asset.ObjectPath(component.Index)}");
        float loop = 0;
        if (positions.Count != 0 && closed) {
            float last = positions.Last!.Value<float>("time");
            float next = Property("bLoopPositionOverride")?.Value<bool>() == true
                ? Property("LoopPosition")?.Value<float>() ?? 0 : last + 1;
            loop = Math.Max(0, position?.Value<float?>("LoopKeyOffset") ?? next - last);
        }
        var distance = new JArray();
        if (curves?["ReparamTable"] is JObject table && table["Points"] is JArray distanceKeys)
            foreach (var point in distanceKeys.OfType<JObject>())
                distance.Add(new JArray(SourceAsset.Number(point["InVal"]), SourceAsset.Number(point["OutVal"])));
        return new JObject { ["component"] = new JObject {
            ["identity"] = asset.ObjectPath(component.Index), ["transform"] = transform,
            ["up"] = Vector(Property("DefaultUpVector"), "XYZ", [0, 0, 1]),
            ["positions"] = positions, ["rotations"] = rotations, ["scales"] = scales,
            ["distance"] = distance, ["loop"] = loop,
            ["reparam_steps"] = Property("ReparamStepsPerSegment")?.Value<int>() ?? 10,
            ["native_spline"] = native
        } };
    }

    private static JArray DefaultKeys(double[] first, double[] second, double[] tangent)
    {
        JObject Key(float time, double[] value) => new() {
            ["time"] = time, ["value"] = new JArray(value), ["arrive"] = new JArray(tangent),
            ["leave"] = new JArray(tangent), ["mode"] = "CUBIC"
        };
        return new JArray(Key(0, first), Key(1, second));
    }

    private static JArray Keys(JObject curve, string channels, bool rotation)
    {
        var result = new JArray();
        if (curve["Points"] is not JArray points) return result;
        foreach (JObject point in points) {
            var defaults = rotation ? new double[] { 0, 0, 0, 1 } : new double[] { 0, 0, 0 };
            string mode = (point.Value<string>("InterpMode") ?? "CIM_Linear").Split("::").Last() switch {
                "0" or "CIM_Linear" => "LINEAR", "2" or "CIM_Constant" => "CONSTANT",
                "1" or "3" or "4" or "5" or "CIM_CurveAuto" or "CIM_CurveUser" or "CIM_CurveBreak" or "CIM_CurveAutoClamped" => "CUBIC",
                var invalid => throw new ConversionException($"Unsupported spline interpolation {invalid}")
            };
            result.Add(new JObject {
                ["time"] = SourceAsset.Number(point["InVal"]), ["value"] = Vector(point["OutVal"], channels, defaults),
                ["arrive"] = Vector(point["ArriveTangent"], channels, defaults),
                ["leave"] = Vector(point["LeaveTangent"], channels, defaults), ["mode"] = mode
            });
        }
        return result;
    }

    private static JArray Vector(JToken? token, string channels, double[] defaults)
        => new JArray(channels.Select((channel, index) => SourceAsset.Number((token as JObject)?[channel.ToString()], defaults[index])));
}
