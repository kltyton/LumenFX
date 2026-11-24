using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Saved LUTs remain authoritative; editor curve controls do not replace the serialized runtime table. */
internal sealed class StatelessDistributionCompiler(StatelessDataBuilder data, StatelessParameterCompiler parameters)
{
    internal StatelessDataBuilder.Distribution Distribution(JToken? source, float[] constructorDefaults,
        bool color = false, bool asCurve = false, float[]? fallback = null)
    {
        JObject value = source as JObject ?? new();
        StatelessDataBuilder.Mode mode = Mode(value, constructorDefaults.Length == 1 ? "UniformConstant" : "NonUniformConstant");
        float[] defaults = fallback ?? (color ? new[] { 1f, 1f, 1f, 1f } : new float[constructorDefaults.Length]);
        float[] samples = value["Values"] is JArray values
            ? values.SelectMany(item => StatelessParameterCompiler.Components(item, new float[constructorDefaults.Length], color)).ToArray()
            : constructorDefaults.Concat(constructorDefaults).ToArray();
        uint[] words = Words(samples), defaultWords = Words(defaults);
        float minimum = (float)SourceAsset.Number(value["ValuesTimeRange"]?["X"], 0);
        float maximum = (float)SourceAsset.Number(value["ValuesTimeRange"]?["Y"], 1);
        byte lookup = value.Value<byte?>("LookupValueMode") ?? 0;
        int parameterOffset = asCurve ? -1 : mode switch {
            StatelessDataBuilder.Mode.Binding => parameters.Binding(value["ParameterBinding"]),
            StatelessDataBuilder.Mode.Expression => parameters.Expression(value["ParameterExpression"], NumericType(constructorDefaults.Length, color)),
            _ => -1
        };
        return asCurve ? data.Curve(mode, words, constructorDefaults.Length, defaultWords, minimum, maximum, lookup)
            : data.Build(mode, words, constructorDefaults.Length, defaultWords, minimum, maximum, lookup,
                EnumValue(value["InterpolationMode"], "Linear") is "Linear" or "0",
                EnumValue(value["AddressMode"], "Clamp") is "Clamp" or "0", parameterOffset);
    }

    internal JObject Range(JToken? source, float[] constructorDefaults, float[] fallback, bool color = false, bool rotator = false)
    {
        JObject value = source as JObject ?? new();
        StatelessDataBuilder.Mode mode = Mode(value, constructorDefaults.Length == 1 ? "UniformConstant" : "NonUniformConstant");
        int offset = mode switch {
            StatelessDataBuilder.Mode.Binding => parameters.Binding(value["ParameterBinding"]),
            StatelessDataBuilder.Mode.Expression => parameters.Expression(value["ParameterExpression"], rotator ? "/Script/CoreUObject.Quat4f" : NumericType(fallback.Length, color)),
            _ => -1
        };
        float[] minimum, maximum;
        if (mode is StatelessDataBuilder.Mode.Binding or StatelessDataBuilder.Mode.Expression) {
            minimum = (float[])fallback.Clone(); maximum = (float[])fallback.Clone();
        } else if (value["Values"] is JArray values) {
            minimum = (float[])fallback.Clone(); maximum = (float[])fallback.Clone();
            bool first = true;
            foreach (JToken item in values) {
                float[] sample = StatelessParameterCompiler.Components(item, fallback, color);
                for (int index = 0; index < sample.Length; index++) {
                    minimum[index] = first ? sample[index] : minimum[index] < sample[index] ? minimum[index] : sample[index];
                    maximum[index] = first ? sample[index] : maximum[index] > sample[index] ? maximum[index] : sample[index];
                }
                first = false;
            }
        } else {
            minimum = StatelessParameterCompiler.Components(value["Min"], constructorDefaults, color, rotator);
            maximum = mode == StatelessDataBuilder.Mode.UniformConstant ? (float[])minimum.Clone()
                : StatelessParameterCompiler.Components(value["Max"], constructorDefaults, color, rotator);
        }
        return new JObject { ["minimum"] = new JArray(Words(minimum)), ["maximum"] = new JArray(Words(maximum)), ["parameter_offset"] = offset };
    }

    internal JObject LiteralRange(JToken? source, params float[] defaults)
    {
        JObject value = source is JObject fields ? (JObject)fields.DeepClone() : new();
        value["Mode"] = Mode(value, defaults.Length == 1 ? "UniformConstant" : "NonUniformConstant") == StatelessDataBuilder.Mode.UniformConstant
            ? "UniformConstant" : "NonUniformRange";
        return Range(value, defaults, defaults);
    }

    internal JObject Scalar(JToken? source, double constructorDefault, bool integer = false, bool clampDuration = false)
    {
        JObject value = source as JObject ?? new();
        StatelessDataBuilder.Mode mode = Mode(value, "UniformConstant");
        double minimum = SourceAsset.Number(value["Min"], constructorDefault), maximum = SourceAsset.Number(value["Max"], constructorDefault);
        if (clampDuration) { minimum = Math.Max(minimum, .0001); maximum = Math.Max(maximum, .0001); }
        string type = integer ? "/Script/Niagara.NiagaraInt32" : "/Script/Niagara.NiagaraFloat";
        int offset = mode switch {
            StatelessDataBuilder.Mode.Binding => parameters.Binding(value["ParameterBinding"]),
            StatelessDataBuilder.Mode.Expression => parameters.Expression(value["ParameterExpression"], type),
            _ => -1
        };
        return new JObject { ["minimum"] = integer ? new JValue(checked((int)minimum)) : new JValue((float)minimum),
            ["maximum"] = integer ? new JValue(checked((int)maximum)) : new JValue((float)maximum),
            ["bound"] = mode is StatelessDataBuilder.Mode.Binding or StatelessDataBuilder.Mode.Expression, ["parameter_offset"] = offset };
    }

    internal JObject IntegerRange(JToken? source, int constructorDefault)
    {
        JObject result = Scalar(source, constructorDefault, integer: true);
        if (result.Value<bool>("bound")) { result["minimum"] = 0; result["maximum"] = 0; }
        else if (source is not JObject value || Mode(value, "UniformConstant") == StatelessDataBuilder.Mode.UniformConstant)
            result["maximum"] = result["minimum"]!.DeepClone();
        return result;
    }

    internal static JArray Packed(StatelessDataBuilder.Distribution value) => new(value.Header, value.TimeBias, value.TimeScale);
    internal static JObject CurveDefaults(JToken? source, int width, bool accumulate = true)
    {
        JToken Point(float value) => width == 1 ? new JValue(value)
            : new JObject(new[] { "X", "Y", "Z", "W" }.Take(width).Select(axis => new JProperty(axis, value)));
        JObject result = new() { ["Mode"] = "UniformCurve", ["Values"] = new JArray(Point(accumulate ? 0f : 1f), Point(1f)) };
        if (source is JObject fields) foreach (JProperty property in fields.Properties()) result[property.Name] = property.Value.DeepClone();
        return result;
    }
    internal static uint[] Words(float[] values) => values.Select(value => unchecked((uint)BitConverter.SingleToInt32Bits(value))).ToArray();
    internal static string EnumValue(JToken? value, string fallback) => value?.Value<string>()?.Split("::").Last() ?? fallback;
    private static StatelessDataBuilder.Mode Mode(JObject value, string fallback)
    {
        string mode = EnumValue(value["Mode"], fallback);
        return Enum.TryParse(mode, out StatelessDataBuilder.Mode result) && Enum.IsDefined(result) ? result
            : throw new ConversionException($"Unknown stateless distribution mode {mode}");
    }
    private static string NumericType(int width, bool color) => width switch {
        1 => "/Script/Niagara.NiagaraFloat", 2 => "/Script/CoreUObject.Vector2f", 3 => "/Script/CoreUObject.Vector3f",
        4 => color ? "/Script/CoreUObject.LinearColor" : "/Script/CoreUObject.Vector4f",
        _ => throw new ConversionException($"Invalid stateless distribution component count {width}")
    };
}
