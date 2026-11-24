using System.Globalization;
using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Expressions;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr ConstantDouble(JObject properties)
    {
        double value = SourceAsset.Number(properties["Value"], 0);
        if (!precision.LwcEnabled) return Constant(value);
        const double tileSize = 2097152.0;
        double tile = Math.Round(value / tileSize, MidpointRounding.ToEven);
        double offset = value - tile * tileSize;
        materialLwcTileUsed = true;
        return UniformExpression($"LfxLWC1({Number((float)tile)}, {Number((float)offset)})", 1, largeWorld: true);
    }

    private Expr IfThenElse(Context context, JObject properties)
    {
        Expr condition = Input(context, properties["Condition"], () => StaticBool(false));
        if (TryReadConstantBool(condition, out bool constantCondition))
            return RequiredInput(context, properties, constantCondition ? "True" : "False");

        Expr boolCondition = CastScalarToMirBoolean(condition);
        Expr thenValue = RequiredInput(context, properties, "True");
        Expr elseValue = RequiredInput(context, properties, "False");
        return MirTernary("Select", boolCondition, thenValue, elseValue);
    }

    private Expr ConvertNode(Context context, SourceObject node, JObject properties, int output)
    {
        JArray inputs = properties["ConvertInputs"] as JArray ?? new JArray();
        JArray outputs = properties["ConvertOutputs"] as JArray ?? new JArray();
        JArray mappings = properties["ConvertMappings"] as JArray ?? new JArray();
        if (output < 0 || output >= outputs.Count)
            throw new ConversionException($"Invalid Convert output index {output} in {context.Asset.Name}:{node.Name}");

        JObject convertOutput = outputs[output] as JObject
            ?? throw new ConversionException($"Invalid Convert output definition at {output} in {context.Asset.Name}:{node.Name}");
        int outputComponents = ParseConvertComponentCount(convertOutput["Type"], context.Asset.Name, node.Name);
        double[] outputDefaults = LinearColorComponents(convertOutput["DefaultValue"]);

        Expr?[] cachedInputs = new Expr?[inputs.Count];
        Expr?[] resolvedComponents = new Expr?[4];

        foreach (JToken token in mappings)
        {
            if (token is not JObject mapping) continue;
            int mappingOutput = mapping.Value<int?>("OutputIndex") ?? 0;
            if (mappingOutput != output) continue;

            int outputComponent = mapping.Value<int?>("OutputComponentIndex") ?? 0;
            if (outputComponent < 0 || outputComponent >= outputComponents)
                throw new ConversionException($"Invalid Convert output component {outputComponent} in {context.Asset.Name}:{node.Name}");

            int inputIndex = mapping.Value<int?>("InputIndex") ?? 0;
            if (inputIndex < 0 || inputIndex >= inputs.Count)
                throw new ConversionException($"Invalid Convert input index {inputIndex} in {context.Asset.Name}:{node.Name}");

            JObject convertInput = inputs[inputIndex] as JObject
                ?? throw new ConversionException($"Invalid Convert input definition at {inputIndex} in {context.Asset.Name}:{node.Name}");
            int inputWidth = ParseConvertComponentCount(convertInput["Type"], context.Asset.Name, node.Name);
            int inputComponent = mapping.Value<int?>("InputComponentIndex") ?? 0;
            if (inputComponent < 0 || inputComponent >= inputWidth)
                throw new ConversionException($"Invalid Convert input component {inputComponent} in {context.Asset.Name}:{node.Name}");

            if (cachedInputs[inputIndex] is null)
            {
                double[] inputDefaults = LinearColorComponents(convertInput["DefaultValue"]);
                Expr raw = Input(context, convertInput["ExpressionInput"], () => Vector(inputDefaults));
                cachedInputs[inputIndex] = Shared(CastConvertInput(raw, inputWidth));
            }

            resolvedComponents[outputComponent] = ExtractScalarComponent(cachedInputs[inputIndex]!, inputComponent);
        }

        for (int component = 0; component < outputComponents; component++)
        {
            resolvedComponents[component] ??= Constant(outputDefaults[component]);
        }

        return CombineScalarComponents(resolvedComponents, outputComponents);
    }

    private Expr ColorRamp(Context context, SourceObject node, JObject properties)
    {
        SourceObject curveObject = ResolveColorRampCurve(context, node, properties["ColorCurve"]);
        double[][] channelTimes = new double[4][];
        double[][] channelValues = new double[4][];
        ReadLinearColorCurveChannels(curveObject, channelTimes, channelValues);

        Expr rawInput = Input(context, properties["Input"],
            () => Constant(SourceAsset.Number(properties["ConstInput"], 0.5)));
        Expr input = Cast(DemoteLargeWorld(rawInput), 1);

        if (double.TryParse(input.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double constantTime))
        {
            return Vector(LinearColorCurveEvaluator.EvaluateLinearColor(curveObject, (float)constantTime));
        }

        Expr sharedInput = Shared(input);
        Expr[] channels = new Expr[4];
        for (int c = 0; c < 4; c++)
            channels[c] = EmitColorRampChannel(sharedInput, channelTimes[c], channelValues[c]);
        return new Expr($"vec4({channels[0].Code}, {channels[1].Code}, {channels[2].Code}, {channels[3].Code})", 4);
    }

    private static bool TryReadConstantBool(Expr value, out bool result)
    {
        if (value.StaticBool is bool staticBool)
        {
            result = staticBool;
            return true;
        }
        if (value.Code == "true")
        {
            result = true;
            return true;
        }
        if (value.Code == "false")
        {
            result = false;
            return true;
        }
        if (value.Width == 1 && !value.LargeWorld
                && double.TryParse(value.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double number))
        {
            result = number != 0.0;
            return true;
        }
        result = false;
        return false;
    }

    private Expr CastScalarToMirBoolean(Expr value)
    {
        if (IsMirBoolean(value)) return MirBoolean(value, 1);
        if (value.LargeWorld) return MirCompare("!=", Swizzle(value, "x"), Constant(0));
        if (value.IsInteger) return ZeroGradient(new Expr($"({Component(value, 0)} != 0)", 1, ShaderBoolean: true));
        return ZeroGradient(new Expr($"({Component(value, 0)} != 0.0)", 1, ShaderBoolean: true));
    }

    private static int ParseConvertComponentCount(JToken? token, string assetName, string nodeName)
    {
        if (token is null || token.Type == JTokenType.Null) return 1;
        if (token.Type == JTokenType.Integer)
        {
            return token.Value<int>() switch {
                0 => 1,
                1 => 2,
                2 => 3,
                3 => 4,
                int invalid => throw new ConversionException($"Invalid Convert type {invalid} in {assetName}:{nodeName}")
            };
        }
        string name = (token.Value<string>() ?? "Scalar").Split("::").Last();
        return name switch {
            "Scalar" or "0" => 1,
            "Vector2" or "1" => 2,
            "Vector3" or "2" => 3,
            "Vector4" or "3" => 4,
            _ => throw new ConversionException($"Invalid Convert type {name} in {assetName}:{nodeName}")
        };
    }

    private static double[] LinearColorComponents(JToken? token) => new[] {
        SourceAsset.Number(token?["R"] ?? token?["X"], 0),
        SourceAsset.Number(token?["G"] ?? token?["Y"], 0),
        SourceAsset.Number(token?["B"] ?? token?["Z"], 0),
        SourceAsset.Number(token?["A"] ?? token?["W"], 1)
    };

    private Expr CastConvertInput(Expr value, int width)
    {
        if (IsMirBoolean(value)) return MirBoolean(value, width);
        if (value.SignedInt) return CastSigned(value, width);
        if (value.UnsignedInt) return CastUnsigned(value, width);
        return Cast(value, width);
    }

    private Expr ExtractScalarComponent(Expr value, int componentIndex)
    {
        if (value.Width == 1) return value;
        char lane = "xyzw"[componentIndex];
        if (value.LargeWorld)
            return new Expr($"LfxLWC1(({value.RawCode}).Tile.{lane}, ({value.RawCode}).Offset.{lane})", 1, LargeWorld: true, Uniform: value.Uniform);
        if (value.ShaderBoolean || value.SignedInt || value.UnsignedInt)
            return ZeroGradient(new Expr($"({value.Code}).{lane}", 1, UnsignedInt: value.UnsignedInt, SignedInt: value.SignedInt, ShaderBoolean: value.ShaderBoolean));
        return Swizzle(value, lane.ToString());
    }

    private Expr CombineScalarComponents(Expr?[] components, int width)
    {
        if (width == 1) return components[0]!;
        bool anyLargeWorld = false, allBool = true, allBoolOrInt = true, anySigned = false, allUniform = true;
        for (int i = 0; i < width; i++)
        {
            Expr c = components[i]!;
            bool isBool = IsMirBoolean(c);
            anyLargeWorld |= c.LargeWorld;
            allBool &= isBool;
            allBoolOrInt &= isBool || c.SignedInt || c.UnsignedInt;
            anySigned |= c.SignedInt;
            allUniform &= c.Uniform;
        }
        if (anyLargeWorld)
        {
            string tiles = string.Join(", ", Enumerable.Range(0, width).Select(i => $"({Promote(components[i]!)}).Tile"));
            string offsets = string.Join(", ", Enumerable.Range(0, width).Select(i => $"({Promote(components[i]!)}).Offset"));
            return new Expr($"LfxLWC{width}(vec{width}({tiles}), vec{width}({offsets}))", width, LargeWorld: true, Uniform: allUniform);
        }
        if (allBool)
        {
            string args = string.Join(", ", Enumerable.Range(0, width).Select(i => MirBoolean(components[i]!, 1).Code));
            return ZeroGradient(new Expr($"bvec{width}({args})", width, ShaderBoolean: true, Uniform: allUniform));
        }
        if (allBoolOrInt)
        {
            if (anySigned)
            {
                string args = string.Join(", ", Enumerable.Range(0, width).Select(i =>
                {
                    Expr c = components[i]!;
                    return CastSigned(IsMirBoolean(c) ? MirBoolean(c, 1) : c, 1).Code;
                }));
                return ZeroGradient(new Expr($"ivec{width}({args})", width, SignedInt: true, Uniform: allUniform));
            }
            string unsignedArgs = string.Join(", ", Enumerable.Range(0, width).Select(i =>
            {
                Expr c = components[i]!;
                return CastUnsigned(IsMirBoolean(c) ? MirBoolean(c, 1) : c, 1).Code;
            }));
            return ZeroGradient(new Expr($"uvec{width}({unsignedArgs})", width, UnsignedInt: true, Uniform: allUniform));
        }
        Expr result = Cast(components[0]!, 1);
        for (int i = 1; i < width; i++) result = Append(result, Cast(components[i]!, 1));
        return result;
    }

    private SourceObject ResolveColorRampCurve(Context context, SourceObject node, JToken? curveRef)
    {
        if (curveRef is JValue { Type: JTokenType.Integer } intRef)
        {
            int exportIndex = intRef.Value<int>();
            if (exportIndex > 0) return context.Asset.Object(exportIndex);
        }
        string curvePath = ObjectPropertyReaderCompiler.ObjectPath(context.Asset, curveRef);
        if (curvePath.Length == 0)
            throw new ConversionException($"Missing ColorCurve in {context.Asset.Name}:{node.Name}");
        SourceAsset curveAsset = library.Load(curvePath);
        return curveAsset.ResolveObject(curvePath, obj => obj.ClassName == "CurveLinearColor");
    }

    private static void ReadLinearColorCurveChannels(SourceObject curveObject, double[][] channelTimes, double[][] channelValues)
    {
        JArray curves = curveObject.Properties["FloatCurves"] as JArray ?? new JArray();
        for (int c = 0; c < 4; c++)
        {
            JObject? channel = c < curves.Count ? curves[c] as JObject : null;
            JArray keys = channel?["Keys"] as JArray ?? new JArray();
            var sorted = keys.OfType<JObject>()
                .Select(k => (Time: SourceAsset.Number(k["Time"]), Value: SourceAsset.Number(k["Value"])))
                .OrderBy(k => k.Time)
                .ToArray();
            channelTimes[c] = sorted.Select(k => k.Time).ToArray();
            channelValues[c] = sorted.Select(k => k.Value).ToArray();
        }
    }

    private Expr EmitColorRampChannel(Expr input, double[] times, double[] values)
    {
        int n = times.Length;
        if (n == 0) return Constant(0);
        if (n == 1) return Constant(values[0]);
        if (n == 2)
        {
            double dt = times[1] - times[0];
            if (Math.Abs(dt) < 1e-12) return Constant(values[1]);
            string fraction = $"(({input.Code} - {Number(times[0])}) / {Number(dt)})";
            return new Expr($"mix({Number(values[0])}, {Number(values[1])}, {fraction})", 1);
        }

        string acc = Number(values[0]);
        int i = 0;
        for (; i < n - 4; i += 4)
        {
            string startTimes = $"vec4({Number(times[i])}, {Number(times[i + 1])}, {Number(times[i + 2])}, {Number(times[i + 3])})";
            string deltas = $"vec4({Number(SafeRampDelta(times[i + 1] - times[i]))}, {Number(SafeRampDelta(times[i + 2] - times[i + 1]))}, {Number(SafeRampDelta(times[i + 3] - times[i + 2]))}, {Number(SafeRampDelta(times[i + 4] - times[i + 3]))})";
            string valueDeltas = $"vec4({Number(values[i + 1] - values[i])}, {Number(values[i + 2] - values[i + 1])}, {Number(values[i + 3] - values[i + 2])}, {Number(values[i + 4] - values[i + 3])})";
            acc = $"({acc} + dot({valueDeltas}, clamp((vec4({input.Code}) - {startTimes}) / {deltas}, 0.0, 1.0)))";
        }
        for (; i < n - 1; i++)
        {
            double dt = SafeRampDelta(times[i + 1] - times[i]);
            double dv = values[i + 1] - values[i];
            if (dv == 0.0) continue;
            acc = $"({acc} + {Number(dv)} * clamp(({input.Code} - {Number(times[i])}) / {Number(dt)}, 0.0, 1.0))";
        }
        return Shared(new Expr(acc, 1));
    }

    private static double SafeRampDelta(double dt) => Math.Abs(dt) < 1e-12 ? 1e-6 : dt;
}
