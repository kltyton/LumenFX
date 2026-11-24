using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Mesh initialization, integrated mesh rotation and decal orientation retain their distinct source units. */
internal sealed class StatelessOrientationCompiler(StatelessModuleCompiler layout, StatelessDistributionCompiler distributions, int sourceVersion)
{
    internal JObject? Compile(string kind, JObject source) => kind switch {
        "NiagaraStatelessModule_InitialMeshOrientation" => Initial(source),
        "NiagaraStatelessModule_MeshRotationRate" => Rate(source),
        "NiagaraStatelessModule_DecalAttributes" => Decal(source),
        _ => null
    };

    private JObject Initial(JObject source)
    {
        bool enabled = source.Value<bool?>("bModuleEnabled") ?? true;
        int mode = enabled ? StatelessDistributionCompiler.EnumValue(source["MeshOrientationMode"], "None") switch {
            "None" or "0" => 0, "Random" or "1" => 1, "OrientToAxis" or "2" => 2,
            var value => throw new ConversionException($"Unknown stateless mesh orientation mode {value}")
        } : 0;
        JObject rotationSource = source["Rotation"] is JObject saved ? (JObject)saved.DeepClone() : new();
        if (sourceVersion < 88) {
            float[] maximum = StatelessParameterCompiler.Components(rotationSource["Max"], new[] { 0f, 0f, 0f });
            float[] random = StatelessParameterCompiler.Components(source["RandomRotationRange_DEPRECATED"] ?? source["RandomRotationRange"], new[] { 360f, 360f, 360f });
            rotationSource["Max"] = new JObject { ["X"] = maximum[0] + random[0], ["Y"] = maximum[1] + random[1], ["Z"] = maximum[2] + random[2] };
        }
        JObject rotation = distributions.Range(enabled && mode != 1 ? rotationSource : null, new[] { 0f, 0f, 0f }, new[] { 0f, 0f, 0f });
        if (mode == 1) rotation["maximum"] = new JArray(StatelessDistributionCompiler.Words(new[] { 360f, 360f, 360f }));
        JObject from = distributions.Range(mode == 2 ? source["MeshAxisToOrient"] : null,
            mode == 2 ? new[] { 1f, 0f, 0f } : new[] { 0f, 0f, 0f }, new[] { 0f, 0f, 0f });
        JObject to = distributions.Range(mode == 2 ? source["OrientationVector"] : null,
            mode == 2 ? new[] { 1f, 0f, 0f } : new[] { 0f, 0f, 0f }, new[] { 0f, 0f, 0f });
        int current = layout.Offset("MeshOrientation", 0), previous = layout.Offset("Previous.MeshOrientation", 0);
        return new JObject { ["mode"] = mode, ["rotation"] = rotation, ["from"] = from, ["to"] = to,
            ["current"] = current, ["previous"] = previous, ["callback"] = current >= 0 || previous >= 0 };
    }

    private JObject? Rate(JObject source)
    {
        int current = layout.Offset("MeshOrientation", 0), previous = layout.Offset("Previous.MeshOrientation", 0);
        if (current < 0 && previous < 0) return null;
        JObject rotation = distributions.Range(source["RotationRateDistribution"], new[] { 0f, 0f, 0f }, new[] { 0f, 0f, 0f });
        foreach (string field in new[] { "minimum", "maximum" }) {
            JArray values = (JArray)rotation[field]!;
            for (int index = 0; index < values.Count; index++) {
                float value = BitConverter.Int32BitsToSingle(unchecked((int)values[index].Value<uint>()));
                values[index] = unchecked((uint)BitConverter.SingleToInt32Bits(value * (1f / 360f)));
            }
        }
        JArray curve = StatelessDistributionCompiler.Packed(distributions.Distribution(
            StatelessDistributionCompiler.CurveDefaults(source.Value<bool?>("bUseRateScale") == true ? source["RateScaleDistribution"] : null, 3),
            new[] { 1f, 1f, 1f }, asCurve: true, fallback: new[] { 1f, 1f, 1f }));
        return new JObject { ["current"] = current, ["previous"] = previous, ["rotation"] = rotation, ["curve"] = curve };
    }

    private JObject? Decal(JObject source)
    {
        bool applyOrientation = source.Value<bool?>("bApplyOrientation") ?? false, applySize = source.Value<bool?>("bApplySize") ?? false,
            applyFade = source.Value<bool?>("bApplyFade") ?? false;
        int orientation = applyOrientation ? layout.Offset("DecalOrientation", 0) : -1,
            size = applySize ? layout.Offset("DecalSize", 0) : -1, fade = applyFade ? layout.Offset("DecalFade", 0) : -1;
        if (orientation < 0 && size < 0 && fade < 0) return null;
        JObject result = new() { ["orientation_component"] = orientation, ["size_component"] = size, ["fade_component"] = fade,
            ["orientation_space"] = StatelessPhysicsCompiler.Space(source["OrientationCoordinateSpace"]) };
        if (applyOrientation) result["orientation"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source["Orientation"], new[] { 0f, 0f, 0f }));
        if (applySize) result["size"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source["Size"], new[] { 50f, 50f, 50f }));
        if (applyFade) result["fade"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source["Fade"], new[] { 1f }));
        return result;
    }
}
